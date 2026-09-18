package com.example.kido.media.music;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaItem;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * A short audio preview clip for a track, generated the first time anyone asks for it
 * and served straight from disk after that.
 *
 * <p>Deliberately synchronous and uncached-in-memory, unlike {@code TeaserClipService}'s
 * queue: trimming an mp3 is sub-second with no video to decode, crop or re-encode, so
 * there is nothing here that benefits from an async job, a state machine, or an admin
 * review step before something goes live — a preview is a fixed, deterministic function
 * of the track and the configured clip length, not curated content.
 */
@Slf4j
@Service
public class TrackPreviewService {

    private final MediaProperties props;
    private final MediaPaths paths;

    private Path cacheRoot;

    public TrackPreviewService(MediaProperties props, MediaPaths paths) {
        this.props = props;
        this.paths = paths;
    }

    @PostConstruct
    void init() {
        cacheRoot = Path.of(props.getMusicPreview().getCacheDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(cacheRoot);
        } catch (IOException ex) {
            log.warn("Could not create music preview cache dir {}: {}", cacheRoot, ex.getMessage());
        }
        log.info("Music previews cached at {} (enabled={})", cacheRoot, props.getMusicPreview().isEnabled());
    }

    /**
     * Resolves the cached preview for a track, generating it first if this is the
     * first request for it.
     *
     * @throws ApiException 503 if previews are disabled, 422 if the track's duration
     *         is not yet known (nothing to base a clip window on), 500 if ffmpeg fails
     */
    public Path resolve(MediaItem item) {
        if (!props.getMusicPreview().isEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Previews are disabled on this server");
        }
        Path cached = cacheRoot.resolve(item.getId() + ".mp3");
        if (Files.isRegularFile(cached)) {
            return cached;
        }
        Double duration = item.getMediaInfo() == null ? null : item.getMediaInfo().getDurationSeconds();
        if (duration == null || duration <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This track has not been probed yet, so no duration is known to clip from");
        }
        generate(item, duration, cached);
        return cached;
    }

    private void generate(MediaItem item, double duration, Path target) {
        Path source = paths.requireWithinRoots(item.getFilePath());
        int clipSeconds = Math.max(1, props.getMusicPreview().getClipSeconds());
        double start = duration * Math.max(0, Math.min(1, props.getMusicPreview().getStartFraction()));
        if (start + clipSeconds > duration) {
            start = Math.max(0, duration - clipSeconds);
        }

        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        List<String> command = List.of(
                props.getFfmpegPath(),
                "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-ss", String.valueOf(start),
                "-i", source.toString(),
                "-t", String.valueOf(clipSeconds),
                "-map", "0:a:0",
                "-c:a", "libmp3lame", "-b:a", "128k",
                "-f", "mp3", partial.toString());

        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(
                    Math.max(1, props.getMusicPreview().getTimeoutSeconds()), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                Files.deleteIfExists(partial);
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Preview generation timed out");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(partial) || Files.size(partial) == 0) {
                Files.deleteIfExists(partial);
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ffmpeg could not clip this track");
            }
            // Renamed into place only once complete, so a concurrent request for the
            // same track never serves a half-written file.
            Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Interrupted while clipping this track");
        } catch (IOException ex) {
            log.warn("Preview generation failed for {}: {}", item.getId(), ex.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not clip this track");
        }
    }
}
