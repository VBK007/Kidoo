package com.example.kido.media.trickplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Generates and serves the sprite sheets behind thumbnail scrubbing.
 *
 * <p>One ffmpeg pass captures a frame every {@code intervalSeconds}, scales it, and
 * tiles frames into grid images. A two-hour film at a 10-second interval is 720 frames
 * — 8 sheets of 10x10 — which the client fetches a handful of and crops locally, so
 * dragging the scrubber costs no network round trips.
 *
 * <p>Generation decodes the entire file, so it is never done inline with a request:
 * {@link #requestGeneration} returns immediately and the work runs on a single
 * background thread. One at a time is deliberate — this competes with live transcodes
 * for the same cores, and a scrub preview is worth less than smooth playback.
 *
 * <p>Sheets live in a dedicated cache directory, never beside the media, so the server
 * never writes into the user's library.
 */
@Slf4j
@Service
public class TrickplayService {

    /** Sheet files ffmpeg is told to produce; anything else is refused unresolved. */
    private static final Pattern SHEET_NAME = Pattern.compile("sheet_(\\d{4})\\.jpg");

    private static final String SHEET_PATTERN = "sheet_%04d.jpg";

    private final MediaProperties props;
    private final MediaPaths paths;
    private final MediaItemRepository items;
    private final TrickplayManifestRepository manifests;

    /** Items currently queued or running, so a second request is not a second ffmpeg. */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "media-trickplay");
        thread.setDaemon(true);
        // Below normal: playback and transcoding matter more than scrub previews.
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        return thread;
    });

    private Path cacheRoot;

    public TrickplayService(MediaProperties props,
                            MediaPaths paths,
                            MediaItemRepository items,
                            TrickplayManifestRepository manifests) {
        this.props = props;
        this.paths = paths;
        this.items = items;
        this.manifests = manifests;
    }

    @PostConstruct
    void init() {
        cacheRoot = Path.of(props.getTrickplay().getCacheDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(cacheRoot);
        } catch (IOException ex) {
            log.warn("Could not create trickplay cache dir {}: {}", cacheRoot, ex.getMessage());
        }
        log.info("Trickplay cache at {} (enabled={})", cacheRoot, props.getTrickplay().isEnabled());
    }

    @Transactional(readOnly = true)
    public Optional<TrickplayManifest> find(String mediaItemId) {
        return manifests.findByMediaItemId(mediaItemId);
    }

    /**
     * Ensures sheets exist for an item, starting generation if they do not.
     *
     * @return the manifest, which may still be {@code GENERATING}
     * @throws ApiException 400 if the item is not video
     */
    @Transactional
    public TrickplayManifest requestGeneration(MediaItem item) {
        if (!item.getType().isVideo()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Thumbnail scrubbing only applies to video");
        }

        Optional<TrickplayManifest> existing = manifests.findByMediaItemId(item.getId());
        if (existing.isPresent()) {
            TrickplayManifest manifest = existing.get();
            if (manifest.getState() != TrickplayManifest.State.FAILED) {
                return manifest;
            }
            // A previous attempt failed; wipe it and try once more.
            manifests.delete(manifest);
            manifests.flush();
        }

        MediaProperties.Trickplay config = props.getTrickplay();
        TrickplayManifest manifest = manifests.save(TrickplayManifest.builder()
                .mediaItemId(item.getId())
                .state(TrickplayManifest.State.GENERATING)
                .intervalSeconds(Math.max(1, config.getIntervalSeconds()))
                .tileWidth(config.getTileWidth())
                .columns(Math.max(1, config.getColumns()))
                .rows(Math.max(1, config.getRows()))
                .cacheDir(cacheRoot.resolve(item.getId()).toString())
                .updatedAt(Instant.now())
                .build());

        String itemId = item.getId();
        String filePath = item.getFilePath();
        if (inFlight.putIfAbsent(itemId, Boolean.TRUE) == null) {
            executor.submit(() -> generate(itemId, filePath));
        }
        return manifest;
    }

    /** Runs on the background thread; never throws into the executor. */
    private void generate(String itemId, String filePath) {
        try {
            Path source = paths.requireWithinRoots(filePath);
            Path directory = cacheRoot.resolve(itemId);
            deleteQuietly(directory);
            Files.createDirectories(directory);

            MediaProperties.Trickplay config = props.getTrickplay();
            int interval = Math.max(1, config.getIntervalSeconds());
            int columns = Math.max(1, config.getColumns());
            int rows = Math.max(1, config.getRows());

            List<String> command = List.of(
                    props.getFfmpegPath(),
                    "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-skip_frame", "nokey",
                    "-i", source.toString(),
                    // fps=1/N samples one frame every N seconds; tile packs them into a grid.
                    "-vf", "fps=1/" + interval
                            + ",scale=" + config.getTileWidth() + ":-2"
                            + ",tile=" + columns + "x" + rows,
                    "-an", "-sn", "-dn",
                    "-q:v", String.valueOf(config.getQuality()),
                    directory.resolve(SHEET_PATTERN).toString());

            log.info("Generating trickplay for item={} interval={}s grid={}x{}",
                    itemId, interval, columns, rows);
            log.debug("ffmpeg command: {}", String.join(" ", command));

            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(directory.resolve("ffmpeg.log").toFile())
                    .start();

            boolean finished = process.waitFor(config.getTimeoutMinutes(), TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                fail(itemId, "Timed out after " + config.getTimeoutMinutes() + " minutes");
                return;
            }
            if (process.exitValue() != 0) {
                fail(itemId, "ffmpeg exited " + process.exitValue() + ": " + tail(directory));
                return;
            }

            int sheets = countSheets(directory);
            if (sheets == 0) {
                fail(itemId, "ffmpeg produced no sheets: " + tail(directory));
                return;
            }
            succeed(itemId, sheets, columns, rows, interval);

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(itemId, "Interrupted");
        } catch (Exception ex) {
            log.warn("Trickplay generation failed for item={}", itemId, ex);
            fail(itemId, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        } finally {
            inFlight.remove(itemId);
        }
    }

    // Not @Transactional: this runs on the background thread, where a self-invoked
    // annotation would be bypassed by the proxy and silently do nothing. Each save is
    // its own transaction, which is all a single-row status update needs.
    private void succeed(String itemId, int sheets, int columns, int rows, int interval) {
        manifests.findByMediaItemId(itemId).ifPresent(manifest -> {
            // The last sheet is usually partly empty, so the frame count is an upper
            // bound; the client stops at the item's real duration anyway.
            manifest.setSheetCount(sheets);
            manifest.setFrameCount(sheets * columns * rows);
            manifest.setTileHeight(inferTileHeight(itemId, manifest));
            manifest.setState(TrickplayManifest.State.READY);
            manifest.setGeneratedAt(Instant.now());
            manifest.setUpdatedAt(Instant.now());
            manifest.setError(null);
            manifests.save(manifest);
            log.info("Trickplay ready for item={}: {} sheets, {}s interval",
                    itemId, sheets, interval);
        });
    }

    /**
     * Tile height follows from the source aspect ratio, which the probe already knows.
     * Zero when unprobed — the client then reads it from the first sheet it loads.
     */
    private int inferTileHeight(String itemId, TrickplayManifest manifest) {
        return items.findById(itemId)
                .map(MediaItem::getMediaInfo)
                .filter(info -> info.getWidth() != null && info.getHeight() != null
                        && info.getWidth() > 0)
                .map(info -> {
                    int height = manifest.getTileWidth() * info.getHeight() / info.getWidth();
                    // scale=W:-2 rounds to an even number of lines.
                    return height % 2 == 0 ? height : height + 1;
                })
                .orElse(0);
    }

    // Not @Transactional, for the same reason as succeed above.
    private void fail(String itemId, String message) {
        manifests.findByMediaItemId(itemId).ifPresent(manifest -> {
            manifest.setState(TrickplayManifest.State.FAILED);
            manifest.setError(message == null ? "Unknown error" : truncate(message, 500));
            manifest.setUpdatedAt(Instant.now());
            manifests.save(manifest);
        });
        log.warn("Trickplay failed for item={}: {}", itemId, message);
    }

    /**
     * Resolves one sheet file for serving.
     *
     * <p>The name is pattern-matched before it is resolved, and the result is confirmed
     * to be inside the item's own cache directory — the name arrives from the URL.
     *
     * @throws ApiException 404 when there is no such sheet, 400 for a malformed name
     */
    @Transactional(readOnly = true)
    public Path sheetFile(String mediaItemId, String sheetName) {
        TrickplayManifest manifest = manifests.findByMediaItemId(mediaItemId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No thumbnail frames for this item"));
        if (!manifest.isReady()) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "Thumbnail frames are still being generated");
        }
        if (sheetName == null || !SHEET_NAME.matcher(sheetName).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid sheet name");
        }
        Path directory = Path.of(manifest.getCacheDir()).toAbsolutePath().normalize();
        Path sheet = directory.resolve(sheetName).normalize();
        if (!sheet.startsWith(cacheRoot) || !sheet.startsWith(directory)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid sheet name");
        }
        if (!Files.isRegularFile(sheet)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Sheet not found");
        }
        return sheet;
    }

    /** Discards cached sheets, e.g. when a file changed on disk. */
    @Transactional
    public void discard(String mediaItemId) {
        manifests.findByMediaItemId(mediaItemId).ifPresent(manifest -> {
            deleteQuietly(Path.of(manifest.getCacheDir()));
            manifests.delete(manifest);
        });
    }

    private static int countSheets(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return (int) files
                    .filter(path -> SHEET_NAME.matcher(path.getFileName().toString()).matches())
                    .count();
        }
    }

    private static String tail(Path directory) {
        try {
            Path log = directory.resolve("ffmpeg.log");
            if (!Files.isRegularFile(log)) {
                return "";
            }
            String content = Files.readString(log).trim();
            return content.length() <= 200 ? content : content.substring(content.length() - 200);
        } catch (IOException ex) {
            return "";
        }
    }

    private void deleteQuietly(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort; the next generation recreates the directory.
                }
            });
        } catch (IOException ex) {
            log.debug("Could not delete {}: {}", directory, ex.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
