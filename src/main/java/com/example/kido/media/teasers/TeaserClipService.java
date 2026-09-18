package com.example.kido.media.teasers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.user.AppUser;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Cuts curated, vertical (9:16) teaser clips from a movie's own file.
 *
 * <p>One worker thread, deliberately, matching {@code DownloadService} and {@code
 * TrickplayService}: a background encode competes with live playback for the same
 * cores, and nothing here is urgent enough to contend for them.
 *
 * <p>The ffmpeg filter graph does two things: a two-stage seek (a coarse pre-input seek
 * to a nearby keyframe, then a small accurate seek after) so cutting a few seconds out
 * of a two-hour file is fast and still lands on the exact requested second, and a
 * centre-crop from 16:9 to 9:16 with an optional horizontal shift for off-centre
 * subjects.
 */
@Slf4j
@Service
public class TeaserClipService {

    /** Ceiling on a requested page size, so one call cannot pull the whole feed. */
    private static final int MAX_PAGE_SIZE = 50;

    private final MediaProperties props;
    private final MediaPaths paths;
    private final TeaserClipRepository clips;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "media-teaser-worker");
        thread.setDaemon(true);
        // Below playback and downloads: nothing here is urgent.
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        return thread;
    });

    private Path outputRoot;

    public TeaserClipService(MediaProperties props, MediaPaths paths, TeaserClipRepository clips) {
        this.props = props;
        this.paths = paths;
        this.clips = clips;
    }

    @PostConstruct
    void init() {
        outputRoot = Path.of(props.getTeasers().getOutputDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(outputRoot);
        } catch (IOException ex) {
            log.warn("Could not create teaser clips dir {}: {}", outputRoot, ex.getMessage());
        }
        recoverInterruptedJobs();
        log.info("Teaser clips at {} (enabled={})", outputRoot, props.getTeasers().isEnabled());
    }

    /**
     * Fails clips that were mid-encode when the server stopped.
     *
     * <p>Their ffmpeg process died with the JVM, so leaving them GENERATING would show
     * an admin a job that can never finish.
     */
    private void recoverInterruptedJobs() {
        List<TeaserClip> stranded = clips.findByStateIn(List.of(TeaserClip.State.GENERATING));
        for (TeaserClip clip : stranded) {
            clip.setState(TeaserClip.State.FAILED);
            clip.setError("The server restarted while this clip was being cut.");
            clip.setCompletedAt(Instant.now());
            deleteQuietly(clip.getOutputPath());
        }
        if (!stranded.isEmpty()) {
            clips.saveAll(stranded);
            log.info("Failed {} teaser clip(s) interrupted by a restart", stranded.size());
        }
        clips.findByStateIn(List.of(TeaserClip.State.QUEUED))
                .forEach(clip -> worker.submit(() -> process(clip.getId())));
    }

    /**
     * Queues a new clip. Returns immediately; the encode runs on the worker thread.
     *
     * <p>Deliberately not {@code @Transactional}: the only write here is {@code
     * clips.save(clip)}, which commits on its own via the repository's own transaction.
     * Wrapping this method in one more would delay that commit until the method
     * returns — after {@code worker.submit} has already handed the job to the worker
     * thread, which could then find no row yet at all.
     */
    public TeaserClip requestGeneration(AppUser admin,
                                        MediaItem item,
                                        double startSeconds,
                                        double endSeconds,
                                        Double horizontalOffset,
                                        String label,
                                        String sourceNote,
                                        Boolean published) {

        if (!props.getTeasers().isEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Teaser clips are disabled on this server");
        }
        if (!item.getType().isVideo()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Only video can have a teaser clip");
        }
        if (endSeconds <= startSeconds || startSeconds < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "endSeconds must be greater than startSeconds, and startSeconds must not be negative");
        }
        double duration = endSeconds - startSeconds;
        if (duration < props.getTeasers().getMinClipSeconds()
                || duration > props.getTeasers().getMaxClipSeconds()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Clip length must be between " + props.getTeasers().getMinClipSeconds()
                            + " and " + props.getTeasers().getMaxClipSeconds() + " seconds");
        }
        MediaInfo info = item.getMediaInfo();
        if (info != null && info.getDurationSeconds() != null
                && endSeconds > info.getDurationSeconds()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "endSeconds is past the end of the movie");
        }
        double offset = horizontalOffset != null
                ? horizontalOffset
                : props.getTeasers().getDefaultHorizontalOffset();
        if (offset < -1.0 || offset > 1.0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "horizontalOffset must be between -1 and 1");
        }

        Path source = paths.requireWithinRoots(item.getFilePath());

        TeaserClip clip = TeaserClip.builder()
                .mediaItemId(item.getId())
                .startSeconds(startSeconds)
                .endSeconds(endSeconds)
                .horizontalOffset(offset)
                .label(label)
                .sourceNote(sourceNote)
                .sourcePath(source.toString())
                .published(published != null ? published : props.getTeasers().isPublishByDefault())
                .createdBy(admin == null ? null : admin.getId())
                .createdAt(Instant.now())
                .build();

        TeaserClip saved = clips.save(clip);
        worker.submit(() -> process(saved.getId()));
        log.info("Teaser clip {} queued for '{}' [{}-{}]",
                saved.getId(), item.getTitle(), startSeconds, endSeconds);
        return saved;
    }

    // --- the worker ---

    /** Runs on the single worker thread; never throws into the executor. */
    private void process(String clipId) {
        TeaserClip clip = clips.findById(clipId).orElse(null);
        if (clip == null || clip.getState() != TeaserClip.State.QUEUED) {
            return;
        }
        Path output = outputRoot.resolve(clipId + ".mp4");
        Process process = null;

        try {
            Path source = paths.requireWithinRoots(clip.getSourcePath());
            Files.createDirectories(outputRoot);

            markGenerating(clipId, output);

            List<String> command = buildCommand(source, output, clip);
            log.info("Cutting teaser clip {}: [{}-{}]", clipId, clip.getStartSeconds(), clip.getEndSeconds());
            log.debug("ffmpeg command: {}", String.join(" ", command));

            process = new ProcessBuilder(command)
                    .redirectError(outputRoot.resolve(clipId + ".log").toFile())
                    .start();

            boolean finished = process.waitFor(props.getTeasers().getTimeoutMinutes(), TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                fail(clipId, "Cutting this clip took longer than "
                        + props.getTeasers().getTimeoutMinutes() + " minutes.", output);
                return;
            }
            if (process.exitValue() != 0) {
                fail(clipId, "ffmpeg failed: " + tailLog(clipId), output);
                return;
            }
            if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                fail(clipId, "ffmpeg produced no file: " + tailLog(clipId), output);
                return;
            }
            complete(clipId, output);

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(clipId, "Interrupted", output);
        } catch (ApiException ex) {
            fail(clipId, ex.getMessage(), output);
        } catch (Exception ex) {
            log.warn("Teaser clip {} failed", clipId, ex);
            fail(clipId, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), output);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private List<String> buildCommand(Path source, Path output, TeaserClip clip) {
        MediaProperties.Teasers cfg = props.getTeasers();
        List<String> command = new ArrayList<>();
        command.add(props.getFfmpegPath());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.add("-y");

        // Two-stage seek: a coarse, fast keyframe seek before -i so a large file is not
        // decoded from byte zero, then a small accurate seek after -i so the cut lands
        // on the exact requested second rather than the nearest keyframe.
        double pad = Math.min(5.0, clip.getStartSeconds());
        command.add("-ss");
        command.add(String.valueOf(clip.getStartSeconds() - pad));
        command.add("-i");
        command.add(source.toString());
        command.add("-ss");
        command.add(String.valueOf(pad));
        command.add("-t");
        command.add(String.valueOf(clip.durationSeconds()));

        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add("0:a:0?");
        command.add("-sn");
        command.add("-dn");

        // Centre-crop 16:9 -> 9:16 on height, then shift the crop window horizontally.
        // The window can move by up to half the leftover width either way; offset in
        // [-1,1] scales that, 0 leaving the crop centred.
        String vf = "crop=ih*9/16:ih:(iw-ih*9/16)/2+(" + clip.getHorizontalOffset() + ")*(iw-ih*9/16)/2:0,"
                + "scale=" + cfg.getOutputWidth() + ":" + cfg.getOutputHeight() + ":flags=lanczos";
        command.add("-vf");
        command.add(vf);

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add(cfg.getPreset());
        command.add("-crf");
        command.add(String.valueOf(cfg.getCrf()));
        command.add("-maxrate");
        command.add(cfg.getMaxBitrateKbps() + "k");
        command.add("-bufsize");
        command.add((cfg.getMaxBitrateKbps() * 2) + "k");
        command.add("-profile:v");
        command.add("high");
        command.add("-pix_fmt");
        command.add("yuv420p");

        command.add("-c:a");
        command.add("aac");
        command.add("-ac");
        command.add("2");
        command.add("-b:a");
        command.add("128k");

        command.add("-movflags");
        command.add("+faststart");
        command.add("-f");
        command.add("mp4");
        command.add(output.toString());
        return command;
    }

    // --- state transitions ---
    //
    // Not @Transactional: these run on the worker thread and are called from within
    // this class, where a self-invoked annotated method is bypassed by the proxy and
    // would silently do nothing. Each is a single findById-and-save, which the
    // repository already wraps in its own transaction.

    private void markGenerating(String clipId, Path output) {
        clips.findById(clipId).ifPresent(clip -> {
            clip.setState(TeaserClip.State.GENERATING);
            clip.setStartedAt(Instant.now());
            clip.setOutputPath(output.toString());
            clips.save(clip);
        });
    }

    private void complete(String clipId, Path output) {
        clips.findById(clipId).ifPresent(clip -> {
            try {
                clip.setFileSize(Files.size(output));
            } catch (IOException ignored) {
                // Size is a nicety; the file exists, which is what matters.
            }
            clip.setState(TeaserClip.State.READY);
            clip.setOutputWidth(props.getTeasers().getOutputWidth());
            clip.setOutputHeight(props.getTeasers().getOutputHeight());
            clip.setCompletedAt(Instant.now());
            clip.setError(null);
            clips.save(clip);
            log.info("Teaser clip {} ready ({} bytes)", clipId, clip.getFileSize());
        });
    }

    private void fail(String clipId, String message, Path output) {
        clips.findById(clipId).ifPresent(clip -> {
            clip.setState(TeaserClip.State.FAILED);
            clip.setError(message == null ? "Unknown error" : message);
            clip.setCompletedAt(Instant.now());
            clips.save(clip);
        });
        deleteQuietly(output == null ? null : output.toString());
        log.warn("Teaser clip {} failed: {}", clipId, message);
    }

    private String tailLog(String clipId) {
        try {
            Path log = outputRoot.resolve(clipId + ".log");
            if (!Files.isRegularFile(log)) {
                return "no ffmpeg output";
            }
            String content = Files.readString(log).trim();
            return content.isEmpty() ? "no ffmpeg output" : content.substring(Math.max(0, content.length() - 300));
        } catch (IOException ex) {
            return "no ffmpeg output";
        }
    }

    private void deleteQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Path resolved = Path.of(path).toAbsolutePath().normalize();
            // Guard: only ever delete inside the teaser output directory.
            if (outputRoot != null && resolved.startsWith(outputRoot)) {
                Files.deleteIfExists(resolved);
                Files.deleteIfExists(Path.of(resolved.toString().replaceAll("\\.mp4$", ".log")));
            }
        } catch (IOException | RuntimeException ex) {
            log.debug("Could not delete {}: {}", path, ex.getMessage());
        }
    }

    // --- client-facing operations ---

    @Transactional(readOnly = true)
    public List<TeaserClip> publishedFor(String mediaItemId) {
        return clips.findByMediaItemIdAndPublishedTrueAndStateOrderBySortOrderAscCreatedAtDesc(
                mediaItemId, TeaserClip.State.READY);
    }

    @Transactional(readOnly = true)
    public List<TeaserClip> allFor(String mediaItemId) {
        return clips.findByMediaItemIdOrderBySortOrderAscCreatedAtDesc(mediaItemId);
    }

    /**
     * One page of the global shorts feed, and the seed that dealt it.
     *
     * <p>A caller that has a seed passes it back with every later page; one that arrives
     * without one is given a fresh seed here, to thread through the rest of the scroll.
     */
    public record FeedPage(List<TeaserClip> clips, int seed, int page, int size,
                           long totalItems, int totalPages) {}

    /**
     * The global feed, shuffled rather than ordered.
     *
     * <p>Shorts are watched as a reel, one after another until something catches — so a
     * fixed order means the same few clips are the only ones anybody ever reaches, and
     * the rest sit behind a scroll nobody makes. Randomising is what gives every clip a
     * turn at being first.
     *
     * <p>What this deliberately is <em>not</em> is {@code order by random()} per query.
     * The feed is paged and each page is its own query, so re-dealing the deck for page 2
     * would show clips already seen on page 1 and skip others entirely — the same defect
     * the comment thread's tiebreak exists to prevent, except certain rather than
     * occasional.
     *
     * <p>So the shuffle is seeded. One seed is one permutation of the whole feed, stable
     * for as long as the caller keeps handing it back, and the next viewer through gets a
     * different seed and so a different order. The permutation is computed here rather
     * than in SQL because it has to be reproducible across pages and identical on
     * PostgreSQL and H2, which share no hash function that would do it in the database.
     *
     * <p>The seed is an int rather than a long because it is echoed to a JavaScript
     * client, which would silently round anything past 2^53 and hand back a seed that is
     * not the one it was given. Four billion permutations is more than a feed needs.
     *
     * <p>The one thing a seed cannot hold still is the feed's membership: publishing a
     * clip mid-scroll inserts it into the shuffled list and shifts everything after it,
     * so a page turned across that edit can still repeat or skip one. That is true of any
     * paged feed over a live table, and not worth a snapshot to fix.
     */
    @Transactional(readOnly = true)
    public FeedPage feed(int page, int size, Integer seed) {
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int pageNumber = Math.max(0, page);
        int usedSeed = seed != null ? seed : ThreadLocalRandom.current().nextInt();

        List<String> order = new ArrayList<>(clips.findShuffleableFeedIds(TeaserClip.State.READY));
        Collections.shuffle(order, new Random(usedSeed));
        // Hand-pinned clips are not part of the draw; they sit above it.
        order.addAll(0, clips.findPinnedFeedIds(TeaserClip.State.READY));

        // Long arithmetic deliberately: a caller is free to ask for page 2,000,000,000,
        // where int multiplication wraps negative and would slice from the wrong end.
        int from = (int) Math.min((long) pageNumber * pageSize, order.size());
        int to = (int) Math.min((long) from + pageSize, order.size());

        List<String> ids = order.subList(from, to);
        Map<String, TeaserClip> rows = clips.findAllById(ids).stream()
                .collect(Collectors.toMap(TeaserClip::getId, clip -> clip));
        // findAllById answers in no particular order, and a clip deleted between the two
        // queries does not answer at all; the id list is what carries the order.
        List<TeaserClip> content = ids.stream()
                .map(rows::get)
                .filter(Objects::nonNull)
                .toList();

        int totalPages = (order.size() + pageSize - 1) / pageSize;
        return new FeedPage(content, usedSeed, pageNumber, pageSize, order.size(), totalPages);
    }

    /** @throws ApiException 404 when the clip is unknown or belongs to a different item */
    @Transactional(readOnly = true)
    public TeaserClip requireForItem(String mediaItemId, String clipId) {
        return clips.findByIdAndMediaItemId(clipId, mediaItemId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "Teaser clip not found"));
    }

    @Transactional
    public TeaserClip setPublished(String mediaItemId, String clipId, boolean published) {
        TeaserClip clip = requireForItem(mediaItemId, clipId);
        clip.setPublished(published);
        return clips.save(clip);
    }

    @Transactional
    public void delete(String mediaItemId, String clipId) {
        TeaserClip clip = requireForItem(mediaItemId, clipId);
        deleteQuietly(clip.getOutputPath());
        clips.delete(clip);
    }

    /** Resolves the generated file for serving, confirmed inside the output directory. */
    @Transactional(readOnly = true)
    public Path fileFor(String mediaItemId, String clipId) {
        TeaserClip clip = requireForItem(mediaItemId, clipId);
        if (!clip.isFetchable()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This clip is not ready yet (" + clip.getState() + ")");
        }
        Path file = Path.of(clip.getOutputPath()).toAbsolutePath().normalize();
        if (!file.startsWith(outputRoot) || !Files.isRegularFile(file)) {
            throw new ApiException(HttpStatus.GONE, "The generated clip is no longer available");
        }
        return file;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}
