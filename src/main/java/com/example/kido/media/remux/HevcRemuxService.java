package com.example.kido.media.remux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Produces a one-time H264/MP4 copy of every HEVC source in the library, so a client
 * that cannot take the original (iOS + Matroska is the case this was built for) is
 * served a cached file instead of forcing a live transcode it would fail anyway.
 *
 * <p>This server's own iGPU (a Haswell-generation Intel HD 4600) has no hardware HEVC
 * decode at all, 8-bit or 10-bit — confirmed by hand, not assumed. {@link
 * com.example.kido.media.stream.TranscodeSessionManager}'s VAAPI path decodes on the
 * GPU, so it simply cannot open an HEVC source, and its software fallback would mean
 * decoding on the CPU live, per session, on a 2-core/4-thread laptop chip: one viewer
 * would be marginal, two would stutter every viewer on the server including ones
 * playing something else entirely. Paying that cost once, in the background, and
 * caching the result is the only version of this that scales past one person.
 *
 * <p>Deliberately not part of {@code LibraryIngestService}'s scan-time backfills
 * ({@code backfillArtwork} and friends): those are seconds per file. This is minutes
 * to hours per file on this hardware, so it runs on its own dedicated thread at its
 * own pace, one file at a time, and never inside a scan.
 *
 * <p>Encode path: software decode (HEVC has no hardware decode here) into a
 * hardware-encoded H264 stream via VAAPI when {@code transcode-hwaccel-enabled} is on
 * — the same GPU that already encodes live transcodes handles this fine, it is only
 * HEVC decode that fails — falling back to a pure software {@code libx264} encode
 * (slower, CPU-only) if that fails or hwaccel is off. Every unknown resolves toward
 * the encode that is guaranteed correct, same principle as {@code
 * PlaybackDecisionService}.
 */
@Slf4j
@Service
public class HevcRemuxService {

    /** No work found; how long to wait before checking again. */
    private static final Duration IDLE_POLL_INTERVAL = Duration.ofMinutes(10);

    private final MediaItemRepository items;
    private final MediaPaths paths;
    private final MediaProperties props;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "media-hevc-remux");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean shuttingDown;
    private Path outputDir;

    public HevcRemuxService(MediaItemRepository items, MediaPaths paths, MediaProperties props) {
        this.items = items;
        this.paths = paths;
        this.props = props;
    }

    @PostConstruct
    void init() {
        if (!props.getRemux().isEnabled()) {
            log.info("HEVC remux backfill disabled (app.media.remux.enabled=false)");
            return;
        }
        outputDir = Path.of(props.getRemux().getDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(outputDir);
        } catch (IOException ex) {
            log.warn("Could not prepare HEVC remux cache dir {}: {}", outputDir, ex.getMessage());
            return;
        }
        worker.submit(this::loop);
        log.info("HEVC remux backfill running, cache at {}", outputDir);
    }

    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        worker.shutdownNow();
    }

    private void loop() {
        while (!shuttingDown) {
            Optional<MediaItem> next = nextCandidate();
            if (next.isEmpty()) {
                sleepQuietly(IDLE_POLL_INTERVAL);
                continue;
            }
            try {
                processOne(next.get());
            } catch (Exception ex) {
                // One bad file must never end the whole backfill.
                log.warn("HEVC remux pass failed unexpectedly for item={}: {}",
                        next.get().getId(), ex.getMessage(), ex);
            }
        }
    }

    private Optional<MediaItem> nextCandidate() {
        List<MediaItem> candidates = items
                .findByMediaInfoVideoCodecAndRemuxedPathIsNullAndRemuxCheckedAtIsNullAndMissingFalse(
                        "hevc", PageRequest.of(0, 1));
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    private void processOne(MediaItem item) {
        Path source;
        try {
            source = paths.requireWithinRoots(item.getFilePath());
        } catch (ApiException ex) {
            // Unreachable disk (unmounted USB, missing file not yet reconciled by a
            // scan) — nothing to remux right now; try again once something changes.
            log.debug("Skipping HEVC remux for item={}, file unreachable: {}",
                    item.getId(), ex.getMessage());
            markChecked(item, null);
            return;
        }

        Path output = outputDir.resolve(UUID.randomUUID() + ".mp4");
        Instant startedAt = Instant.now();
        log.info("Starting HEVC remux item={} title='{}' -> {}", item.getId(), item.getTitle(), output);

        boolean ok = false;
        if (props.isTranscodeHwaccelEnabled()) {
            ok = runFfmpeg(buildHwaccelCommand(source, output), output);
            if (!ok) {
                log.info("HEVC remux item={} hwaccel encode failed, retrying in software", item.getId());
                deleteQuietly(output);
            }
        }
        if (!ok) {
            ok = runFfmpeg(buildSoftwareCommand(source, output), output);
        }

        Duration elapsed = Duration.between(startedAt, Instant.now());
        if (ok) {
            log.info("Finished HEVC remux item={} in {}s -> {}",
                    item.getId(), elapsed.toSeconds(), output);
            markChecked(item, output.toString());
        } else {
            log.warn("HEVC remux failed for item={} title='{}' after {}s; will not retry",
                    item.getId(), item.getTitle(), elapsed.toSeconds());
            deleteQuietly(output);
            markChecked(item, null);
        }
    }

    private void markChecked(MediaItem item, String remuxedPath) {
        item.setRemuxCheckedAt(Instant.now());
        if (remuxedPath != null) {
            item.setRemuxedPath(remuxedPath);
        }
        item.setUpdatedAt(Instant.now());
        items.save(item);
    }

    private List<String> buildHwaccelCommand(Path source, Path output) {
        List<String> command = new ArrayList<>();
        command.add(props.getFfmpegPath());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("warning");
        command.add("-nostdin");
        command.add("-y");

        // Decode in software (this GPU cannot decode HEVC at all) but still encode on
        // the GPU: the device is only initialised for the output side, so frames are
        // uploaded to it explicitly below rather than decoded onto it like the live
        // transcode path does for its H264/VP9/etc. sources.
        command.add("-init_hw_device");
        command.add("vaapi=va:" + props.getVaapiDevice());
        command.add("-filter_hw_device");
        command.add("va");

        command.add("-i");
        command.add(source.toString());

        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add("0:a:0?");
        command.add("-sn");
        command.add("-dn");

        command.add("-vf");
        command.add("format=nv12,hwupload");
        command.add("-c:v");
        command.add("h264_vaapi");
        command.add("-profile:v");
        command.add("high");
        command.add("-level");
        command.add("41");

        addAudioAndOutputArgs(command, output);
        return command;
    }

    private List<String> buildSoftwareCommand(Path source, Path output) {
        List<String> command = new ArrayList<>();
        command.add(props.getFfmpegPath());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("warning");
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
        command.add(props.getRemux().getPreset());
        command.add("-crf");
        command.add(String.valueOf(props.getRemux().getCrf()));
        command.add("-profile:v");
        command.add("high");
        command.add("-level");
        command.add("4.1");
        command.add("-pix_fmt");
        command.add("yuv420p");

        addAudioAndOutputArgs(command, output);
        return command;
    }

    private void addAudioAndOutputArgs(List<String> command, Path output) {
        command.add("-c:a");
        command.add("aac");
        command.add("-ac");
        command.add("2");
        command.add("-b:a");
        command.add("192k");
        // Metadata (moov atom) at the front, so a browser or player can start
        // rendering before the whole file has downloaded, same as any web-served MP4.
        command.add("-movflags");
        command.add("+faststart");
        command.add(output.toString());
    }

    private boolean runFfmpeg(List<String> command, Path output) {
        log.debug("HEVC remux ffmpeg command: {}", String.join(" ", command));
        Path errorLog = output.resolveSibling(output.getFileName() + ".log");
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    // To a file, not a pipe: an undrained stderr pipe fills and
                    // deadlocks ffmpeg, exactly as in TranscodeSessionManager.
                    .redirectError(errorLog.toFile())
                    .start();
        } catch (IOException ex) {
            log.warn("Could not start ffmpeg for HEVC remux ({}): {}",
                    props.getFfmpegPath(), ex.getMessage());
            return false;
        }

        boolean finished;
        try {
            finished = process.waitFor(props.getRemux().getTimeoutMinutes(), TimeUnit.MINUTES);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return false;
        }
        if (!finished) {
            log.warn("HEVC remux timed out after {}m, killing ffmpeg", props.getRemux().getTimeoutMinutes());
            process.destroyForcibly();
            deleteQuietly(errorLog);
            return false;
        }

        int exit = process.exitValue();
        if (exit != 0) {
            log.warn("HEVC remux ffmpeg exited {}: {}", exit, tail(errorLog));
            deleteQuietly(errorLog);
            return false;
        }
        deleteQuietly(errorLog);
        boolean produced;
        try {
            produced = Files.isRegularFile(output) && Files.size(output) > 0;
        } catch (IOException ex) {
            produced = false;
        }
        if (!produced) {
            log.warn("HEVC remux exited 0 but produced nothing usable: {}", output);
        }
        return produced;
    }

    private String tail(Path log) {
        try {
            String content = Files.readString(log, StandardCharsets.UTF_8).trim();
            return content.length() <= 400 ? content : content.substring(content.length() - 400);
        } catch (IOException ex) {
            return "";
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort scratch cleanup; the next run's own output name never collides.
        }
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
