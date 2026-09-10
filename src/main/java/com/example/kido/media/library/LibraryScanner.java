package com.example.kido.media.library;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaFiles;
import com.example.kido.media.MediaPaths;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Walks the configured libraries and hands each media file to {@link
 * LibraryIngestService}, tagged with the type of the library it came from.
 *
 * <p>Runs on a single background thread. A scan is IO-bound on a home-server disk and
 * spawns an ffprobe process per new video, so parallel walkers would mostly add
 * contention. Only one scan runs at a time — a concurrent request is rejected rather
 * than queued, since two walks would race on the same rows.
 */
@Slf4j
@Service
public class LibraryScanner {

    /** Bounds recursion on a pathological or deeply nested tree. */
    private static final int MAX_DEPTH = 12;

    private final MediaPaths paths;
    private final LibraryIngestService ingest;

    private final ScanStatus status = new ScanStatus();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "media-library-scan");
        thread.setDaemon(true);
        return thread;
    });

    public LibraryScanner(MediaPaths paths, LibraryIngestService ingest) {
        this.paths = paths;
        this.ingest = ingest;
    }

    public ScanStatus status() {
        return status;
    }

    /**
     * Starts a scan on the background thread and returns immediately.
     *
     * @throws ApiException 400 if no libraries are configured, 409 if a scan is running
     */
    public void startAsync() {
        if (!paths.isConfigured()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "No media libraries configured — set app.media.libraries");
        }
        synchronized (status) {
            if (status.isRunning()) {
                throw new ApiException(HttpStatus.CONFLICT, "A library scan is already running");
            }
            status.begin();
        }
        executor.submit(this::runScan);
    }

    private void runScan() {
        log.info("Library scan started over {} libraries", paths.libraryRoots().size());
        try {
            Set<String> seenPaths = new LinkedHashSet<>();
            for (MediaPaths.LibraryRoot library : paths.libraryRoots()) {
                scanLibrary(library, seenPaths);
            }
            status.countMissing(ingest.markMissing(seenPaths));
            int backfilled = ingest.backfillArtwork();
            status.finish(null);
            log.info("Library scan finished: {} seen, {} added, {} updated, {} unchanged, "
                            + "{} missing, {} failed, {} backfilled artwork",
                    status.getFilesSeen().get(), status.getAdded().get(), status.getUpdated().get(),
                    status.getUnchanged().get(), status.getMarkedMissing().get(),
                    status.getFailed().get(), backfilled);
        } catch (Exception ex) {
            log.error("Library scan failed", ex);
            status.finish(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private void scanLibrary(MediaPaths.LibraryRoot library, Set<String> seenPaths) {
        // FOLLOW_LINKS is deliberately omitted: a symlink cycle would hang the walk, and
        // MediaPaths would refuse to serve anything a link resolved outside the roots.
        try (Stream<Path> walk = Files.walk(library.path(), MAX_DEPTH, new FileVisitOption[0])) {
            List<Path> candidates = walk
                    .filter(Files::isRegularFile)
                    .filter(MediaFiles::isMedia)
                    .toList();

            log.info("Library '{}' [{}]: {} candidate files under {}",
                    library.name(), library.type(), candidates.size(), library.path());

            for (Path file : candidates) {
                status.countSeen();
                status.setCurrentFile(file.toString());
                try {
                    LibraryIngestService.Outcome outcome = ingest.ingest(file, library);
                    switch (outcome) {
                        case ADDED -> status.countAdded();
                        case UPDATED -> status.countUpdated();
                        case UNCHANGED -> status.countUnchanged();
                        case SKIPPED -> { /* extras, artwork and undersized files */ }
                    }
                    if (outcome != LibraryIngestService.Outcome.SKIPPED) {
                        seenPaths.add(file.toAbsolutePath().normalize().toString());
                    }
                } catch (Exception ex) {
                    // One unreadable file must not abort the rest of the library.
                    status.countFailed();
                    log.warn("Skipped {}: {}", file, ex.toString());
                }
            }
        } catch (IOException ex) {
            log.warn("Could not walk library '{}' at {}: {}",
                    library.name(), library.path(), ex.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Library scan thread did not stop within 5s");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
