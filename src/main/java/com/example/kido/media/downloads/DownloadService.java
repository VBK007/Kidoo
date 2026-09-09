package com.example.kido.media.downloads;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.dto.PlaybackDtos.ClientCapabilitiesRequest;
import com.example.kido.media.stream.PlaybackDecisionService;
import com.example.kido.profile.Profile;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Prepares offline copies, converting only when the original will not do.
 *
 * <p>The passthrough case matters more than the conversion one. A file that already
 * plays directly at the requested size is offered as-is: no CPU, no wait, no second
 * generation of compression. Only a file that fails that test is re-encoded, and then
 * with a slower preset than live playback uses, because nothing is waiting on it
 * frame-by-frame and a smaller file is the entire point.
 *
 * <p>One worker thread, deliberately. Preparing a download competes with live
 * transcodes for the same cores, and someone watching now matters more than someone
 * packing for a flight later. Jobs are taken oldest-first.
 *
 * <h2>Progress</h2>
 * ffmpeg is run with {@code -progress pipe:1}, which emits machine-readable
 * {@code key=value} lines. Those are parsed against the probed duration to give the
 * real percentage the client shows. Reading that stream is also what keeps the process
 * from blocking on a full pipe, so the progress reader is load-bearing rather than
 * merely informative.
 */
@Slf4j
@Service
public class DownloadService {

    /** States that count against a profile's queue allowance. */
    private static final List<DownloadJob.State> PENDING_STATES =
            List.of(DownloadJob.State.QUEUED, DownloadJob.State.CONVERTING);

    private static final List<DownloadJob.State> LIVE_STATES =
            List.of(DownloadJob.State.QUEUED, DownloadJob.State.CONVERTING,
                    DownloadJob.State.READY);

    private final MediaProperties props;
    private final MediaPaths paths;
    private final DownloadJobRepository jobs;
    private final MediaItemRepository items;
    private final PlaybackDecisionService decisions;

    /** Running ffmpeg processes by job id, so a cancel can actually stop one. */
    private final Map<String, Process> running = new ConcurrentHashMap<>();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "media-download-worker");
        thread.setDaemon(true);
        // Below playback: a download can wait, a stream cannot.
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        return thread;
    });

    private Path cacheRoot;

    public DownloadService(MediaProperties props,
                           MediaPaths paths,
                           DownloadJobRepository jobs,
                           MediaItemRepository items,
                           PlaybackDecisionService decisions) {
        this.props = props;
        this.paths = paths;
        this.jobs = jobs;
        this.items = items;
        this.decisions = decisions;
    }

    @PostConstruct
    void init() {
        cacheRoot = Path.of(props.getDownloads().getDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(cacheRoot);
        } catch (IOException ex) {
            log.warn("Could not create downloads dir {}: {}", cacheRoot, ex.getMessage());
        }
        recoverInterruptedJobs();
        log.info("Download cache at {} (enabled={})", cacheRoot, props.getDownloads().isEnabled());
    }

    /**
     * Fails jobs that were mid-conversion when the server stopped.
     *
     * <p>Their ffmpeg process died with the JVM, so leaving them as CONVERTING would
     * show a client a progress bar that can never move.
     */
    private void recoverInterruptedJobs() {
        List<DownloadJob> stranded = jobs.findByStateIn(List.of(DownloadJob.State.CONVERTING));
        for (DownloadJob job : stranded) {
            job.setState(DownloadJob.State.FAILED);
            job.setError("The server restarted while this copy was being prepared.");
            job.setCompletedAt(Instant.now());
            deleteQuietly(job.getOutputPath());
        }
        if (!stranded.isEmpty()) {
            jobs.saveAll(stranded);
            log.info("Failed {} download job(s) interrupted by a restart", stranded.size());
        }
        // Anything left queued is picked up again below.
        jobs.findByStateIn(List.of(DownloadJob.State.QUEUED))
                .forEach(job -> worker.submit(() -> process(job.getId())));
    }

    /**
     * Requests an offline copy.
     *
     * <p>An existing live job for the same profile and item is returned rather than
     * duplicated — tapping download twice is a double-tap, not a request for two copies.
     */
    @Transactional
    public DownloadJob request(Profile profile,
                               MediaItem item,
                               Integer requestedHeight,
                               ClientCapabilitiesRequest capabilities) {

        if (!props.getDownloads().isEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Offline downloads are disabled on this server");
        }
        if (!item.getType().isVideo()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Only video can be prepared for offline viewing");
        }

        Optional<DownloadJob> existing = jobs
                .findFirstByProfileIdAndMediaItemIdAndStateIn(
                        profile.getId(), item.getId(), LIVE_STATES);
        if (existing.isPresent()) {
            return existing.get();
        }

        long pending = jobs.countByProfileIdAndStateIn(profile.getId(), PENDING_STATES);
        if (pending >= props.getDownloads().getMaxQueuedPerProfile()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "You already have " + pending + " downloads being prepared; "
                            + "wait for one to finish");
        }

        Path source = paths.requireWithinRoots(item.getFilePath());
        MediaInfo info = item.getMediaInfo();
        int height = requestedHeight != null && requestedHeight > 0
                ? requestedHeight
                : props.getDownloads().getDefaultHeight();

        boolean passthrough = canPassThrough(item, capabilities, height);
        DownloadJob job = DownloadJob.builder()
                .profileId(profile.getId())
                .mediaItemId(item.getId())
                .itemTitle(item.getTitle())
                .mode(passthrough ? DownloadJob.Mode.PASSTHROUGH : DownloadJob.Mode.CONVERT)
                .sourcePath(source.toString())
                .sourceHeight(info == null ? null : info.getHeight())
                .targetHeight(passthrough ? null : height)
                .createdAt(Instant.now())
                .build();

        if (passthrough) {
            // Nothing to do: the original is the deliverable.
            job.setState(DownloadJob.State.READY);
            job.setOutputPath(source.toString());
            job.setFileSize(item.getFileSize());
            job.setEstimatedBytes(item.getFileSize());
            job.setPercent(100);
            job.setCompletedAt(Instant.now());
            job.setExpiresAt(null);
            DownloadJob saved = jobs.save(job);
            log.info("Download {} ready immediately (passthrough) for '{}'",
                    saved.getId(), item.getTitle());
            return saved;
        }

        job.setEstimatedBytes(estimateConvertedBytes(info, height));
        job.setExpiresAt(Instant.now().plus(
                props.getDownloads().getRetentionHours(), ChronoUnit.HOURS));
        DownloadJob saved = jobs.save(job);

        worker.submit(() -> process(saved.getId()));
        log.info("Download {} queued: '{}' to {}p", saved.getId(), item.getTitle(), height);
        return saved;
    }

    /**
     * Whether the original file can be handed over untouched.
     *
     * <p>Reuses the playback decision so an offline copy is judged by exactly the same
     * rule as a live stream — if the device could direct-play it now, it can play the
     * same bytes on a plane. The extra height test is because a download has a second
     * constraint playback does not: a 4K file the phone can decode may still be far
     * larger than someone wants on their storage.
     */
    private boolean canPassThrough(MediaItem item,
                                   ClientCapabilitiesRequest capabilities,
                                   int requestedHeight) {
        if (capabilities == null) {
            return false;
        }
        MediaInfo info = item.getMediaInfo();
        if (info == null || !info.isProbed()) {
            return false;
        }
        if (info.getHeight() != null && info.getHeight() > requestedHeight) {
            return false;
        }
        return decisions.decide(item, capabilities).directPlay();
    }

    /**
     * Size guess for the client's data-plan warning, from the target height.
     *
     * <p>Bitrates per resolution rather than a fraction of the source: a 4K remux and a
     * 4K web release differ by an order of magnitude, so scaling the original size would
     * give a wildly wrong answer for one of them.
     */
    private static Long estimateConvertedBytes(MediaInfo info, int height) {
        if (info == null || info.getDurationSeconds() == null) {
            return null;
        }
        long videoBitsPerSecond = switch (height) {
            case 2160 -> 14_000_000L;
            case 1440 -> 8_000_000L;
            case 1080 -> 4_500_000L;
            case 720 -> 2_500_000L;
            case 480 -> 1_200_000L;
            default -> height >= 1080 ? 4_500_000L : 2_000_000L;
        };
        long audioBitsPerSecond = 160_000L;
        double seconds = info.getDurationSeconds();
        return Math.round(seconds * (videoBitsPerSecond + audioBitsPerSecond) / 8.0);
    }

    // --- the worker ---

    /** Runs on the single worker thread; never throws into the executor. */
    private void process(String jobId) {
        DownloadJob job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getState() != DownloadJob.State.QUEUED) {
            return;
        }
        Path output = cacheRoot.resolve(jobId + ".mp4");
        Process process = null;

        try {
            Path source = paths.requireWithinRoots(job.getSourcePath());
            Files.createDirectories(cacheRoot);

            markConverting(jobId, output);

            List<String> command = buildCommand(source, output, job.getTargetHeight());
            log.info("Preparing download {}: '{}' to {}p",
                    jobId, job.getItemTitle(), job.getTargetHeight());
            log.debug("ffmpeg command: {}", String.join(" ", command));

            process = new ProcessBuilder(command)
                    // stderr to a file so a diagnostic cannot fill an undrained pipe;
                    // stdout is the progress stream and is read below.
                    .redirectError(cacheRoot.resolve(jobId + ".log").toFile())
                    .start();
            running.put(jobId, process);

            Double duration = durationOf(jobId);
            readProgress(process, jobId, duration);

            boolean finished = process.waitFor(
                    props.getDownloads().getTimeoutMinutes(), TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                fail(jobId, "Preparing this copy took longer than "
                        + props.getDownloads().getTimeoutMinutes() + " minutes.", output);
                return;
            }
            if (running.remove(jobId) == null) {
                // Removed by cancel(), which already set the state and cleaned up.
                return;
            }
            if (process.exitValue() != 0) {
                fail(jobId, "Conversion failed: " + tailLog(jobId), output);
                return;
            }
            if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                fail(jobId, "Conversion produced no file: " + tailLog(jobId), output);
                return;
            }
            complete(jobId, output);

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(jobId, "Interrupted", output);
        } catch (ApiException ex) {
            fail(jobId, ex.getMessage(), output);
        } catch (Exception ex) {
            log.warn("Download {} failed", jobId, ex);
            fail(jobId, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                    output);
        } finally {
            running.remove(jobId);
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            // Take the next queued job, if any.
            jobs.findFirstByStateOrderByCreatedAtAsc(DownloadJob.State.QUEUED)
                    .ifPresent(next -> worker.submit(() -> process(next.getId())));
        }
    }

    private List<String> buildCommand(Path source, Path output, Integer height) {
        List<String> command = new ArrayList<>();
        command.add(props.getFfmpegPath());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.add("-y");
        command.add("-i");
        command.add(source.toString());

        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add("0:a:0?");
        command.add("-sn");
        command.add("-dn");

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add(props.getDownloads().getPreset());
        command.add("-crf");
        command.add(String.valueOf(props.getDownloads().getCrf()));
        command.add("-profile:v");
        command.add("high");
        command.add("-level");
        command.add("4.1");
        command.add("-pix_fmt");
        command.add("yuv420p");
        // Never upscale: -2 keeps the aspect and an even width, min() caps at the source.
        command.add("-vf");
        command.add("scale=-2:'min(ih," + height + ")'");

        command.add("-c:a");
        command.add("aac");
        command.add("-ac");
        command.add("2");
        command.add("-b:a");
        command.add("160k");

        // A phone player needs the index at the front to seek a local file.
        command.add("-movflags");
        command.add("+faststart");
        command.add("-f");
        command.add("mp4");

        // Machine-readable progress on stdout, and no duplicate stats on stderr.
        command.add("-progress");
        command.add("pipe:1");
        command.add("-nostats");
        command.add(output.toString());
        return command;
    }

    /**
     * Reads ffmpeg's progress stream and updates the job's percentage.
     *
     * <p>Blocks until ffmpeg closes stdout, which is what drains the pipe. Percentages
     * are written back at most once a second: the stream emits several times a second
     * and a database write per line would be pointless load for a bar the client polls.
     */
    private void readProgress(Process process, String jobId, Double durationSeconds) {
        long lastWrite = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();

                Double seconds = switch (key) {
                    case "out_time_us" -> parseMicros(value);
                    case "out_time" -> parseTimestamp(value);
                    default -> null;
                };
                if (seconds == null || durationSeconds == null || durationSeconds <= 0) {
                    continue;
                }
                double percent = Math.max(0, Math.min(99.9, seconds / durationSeconds * 100.0));
                long now = System.currentTimeMillis();
                if (now - lastWrite >= 1000) {
                    updatePercent(jobId, percent);
                    lastWrite = now;
                }
            }
        } catch (IOException ex) {
            log.debug("Progress stream for {} ended: {}", jobId, ex.getMessage());
        }
    }

    private static Double parseMicros(String value) {
        try {
            long micros = Long.parseLong(value);
            return micros <= 0 ? null : micros / 1_000_000.0;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** {@code out_time} is {@code HH:MM:SS.microseconds}. */
    private static Double parseTimestamp(String value) {
        String[] parts = value.split(":");
        if (parts.length != 3) {
            return null;
        }
        try {
            return Integer.parseInt(parts[0]) * 3600.0
                    + Integer.parseInt(parts[1]) * 60.0
                    + Double.parseDouble(parts[2]);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // --- state transitions ---
    //
    // Not @Transactional: these run on the worker thread and are called from within
    // this class, where a self-invoked annotated method is bypassed by the proxy and
    // would silently do nothing. Each is a single findById-and-save, which the
    // repository already wraps in its own transaction.

    private void markConverting(String jobId, Path output) {
        jobs.findById(jobId).ifPresent(job -> {
            job.setState(DownloadJob.State.CONVERTING);
            job.setStartedAt(Instant.now());
            job.setOutputPath(output.toString());
            jobs.save(job);
        });
    }

    private void updatePercent(String jobId, double percent) {
        jobs.findById(jobId).ifPresent(job -> {
            if (job.getState() == DownloadJob.State.CONVERTING) {
                job.setPercent(Math.round(percent * 10) / 10.0);
                jobs.save(job);
            }
        });
    }

    private void complete(String jobId, Path output) {
        jobs.findById(jobId).ifPresent(job -> {
            try {
                job.setFileSize(Files.size(output));
            } catch (IOException ignored) {
                // Size is a nicety; the file exists, which is what matters.
            }
            job.setState(DownloadJob.State.READY);
            job.setPercent(100);
            job.setCompletedAt(Instant.now());
            job.setExpiresAt(Instant.now().plus(
                    props.getDownloads().getRetentionHours(), ChronoUnit.HOURS));
            job.setError(null);
            jobs.save(job);
            log.info("Download {} ready: '{}' ({} bytes)",
                    jobId, job.getItemTitle(), job.getFileSize());
        });
    }

    private void fail(String jobId, String message, Path output) {
        jobs.findById(jobId).ifPresent(job -> {
            job.setState(DownloadJob.State.FAILED);
            job.setError(message == null ? "Unknown error" : message);
            job.setCompletedAt(Instant.now());
            jobs.save(job);
        });
        deleteQuietly(output == null ? null : output.toString());
        log.warn("Download {} failed: {}", jobId, message);
    }

    /**
     * The probed duration, which is what a percentage is measured against.
     *
     * <p>Null when the file was never probed, in which case progress stays at zero and
     * the client shows an indeterminate bar rather than a fabricated number.
     */
    private Double durationOf(String jobId) {
        return jobs.findById(jobId)
                .flatMap(job -> items.findById(job.getMediaItemId()))
                .map(MediaItem::getMediaInfo)
                .map(MediaInfo::getDurationSeconds)
                .orElse(null);
    }

    // --- client-facing operations ---

    @Transactional(readOnly = true)
    public List<DownloadJob> listFor(Profile profile) {
        return jobs.findByProfileIdOrderByCreatedAtDesc(profile.getId());
    }

    /** @throws ApiException 404 when the job is unknown or belongs to another profile */
    @Transactional(readOnly = true)
    public DownloadJob require(Profile profile, String jobId) {
        return jobs.findByIdAndProfileId(jobId, profile.getId()).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "Download not found"));
    }

    /**
     * Resolves the prepared file for serving.
     *
     * <p>A passthrough job points at the original, which is re-validated against the
     * media roots. A converted job points inside the download cache, which is confirmed
     * the same way — the path comes from a stored row either way.
     */
    @Transactional
    public Path fileFor(Profile profile, String jobId) {
        DownloadJob job = require(profile, jobId);
        if (!job.isFetchable()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This copy is not ready yet (" + job.getState() + ")");
        }
        Path file;
        if (job.getMode() == DownloadJob.Mode.PASSTHROUGH) {
            file = paths.requireWithinRoots(job.getOutputPath());
        } else {
            file = Path.of(job.getOutputPath()).toAbsolutePath().normalize();
            if (!file.startsWith(cacheRoot) || !Files.isRegularFile(file)) {
                throw new ApiException(HttpStatus.GONE,
                        "The prepared copy is no longer available; request it again");
            }
        }
        job.setLastFetchedAt(Instant.now());
        jobs.save(job);
        return file;
    }

    /** Cancels a job, killing ffmpeg if it is running, and deletes any partial file. */
    @Transactional
    public DownloadJob cancel(Profile profile, String jobId) {
        DownloadJob job = require(profile, jobId);

        Process process = running.remove(jobId);
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        // A passthrough keeps no artifact, so there is nothing to delete but the row.
        if (job.getMode() == DownloadJob.Mode.CONVERT) {
            deleteQuietly(job.getOutputPath());
        }
        job.setState(DownloadJob.State.CANCELLED);
        job.setCompletedAt(Instant.now());
        job.setPercent(0);
        DownloadJob saved = jobs.save(job);
        log.info("Download {} cancelled", jobId);
        return saved;
    }

    /**
     * Deletes prepared copies past their retention window.
     *
     * <p>Only touches the download cache, and only files this service made — a
     * passthrough job is left entirely alone, since its "output" is the user's own
     * media file.
     */
    @Transactional
    public int expireOldJobs() {
        List<DownloadJob> expired = jobs.findExpired(DownloadJob.State.READY, Instant.now());
        for (DownloadJob job : expired) {
            if (job.getMode() == DownloadJob.Mode.CONVERT) {
                deleteQuietly(job.getOutputPath());
            }
            job.setState(DownloadJob.State.EXPIRED);
            job.setOutputPath(null);
            jobs.save(job);
        }
        if (!expired.isEmpty()) {
            log.info("Expired {} prepared download(s)", expired.size());
        }
        return expired.size();
    }

    /** Total bytes the download cache is holding, for the admin Disk tab. */
    public long cacheBytes() {
        if (cacheRoot == null || !Files.isDirectory(cacheRoot)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(cacheRoot)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ex) {
                    return 0;
                }
            }).sum();
        } catch (IOException ex) {
            return 0;
        }
    }

    private void deleteQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Path resolved = Path.of(path).toAbsolutePath().normalize();
            // Guard: only ever delete inside the download cache.
            if (cacheRoot != null && resolved.startsWith(cacheRoot)) {
                Files.deleteIfExists(resolved);
                Files.deleteIfExists(Path.of(resolved.toString()
                        .replaceAll("\\.mp4$", ".log")));
            }
        } catch (IOException | RuntimeException ex) {
            log.debug("Could not delete {}: {}", path, ex.getMessage());
        }
    }

    private String tailLog(String jobId) {
        try {
            Path log = cacheRoot.resolve(jobId + ".log");
            if (!Files.isRegularFile(log)) {
                return "no ffmpeg output";
            }
            String content = Files.readString(log, StandardCharsets.UTF_8).trim();
            return content.isEmpty()
                    ? "no ffmpeg output"
                    : content.substring(Math.max(0, content.length() - 300));
        } catch (IOException ex) {
            return "no ffmpeg output";
        }
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
        running.values().forEach(Process::destroyForcibly);
        running.clear();
    }
}
