package com.example.kido.media;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.example.kido.common.ApiException;

import lombok.extern.slf4j.Slf4j;

/**
 * The module's single gate onto the filesystem.
 *
 * <p>Every path the server opens — video, artwork, subtitle — is resolved through
 * {@link #requireWithinRoots}. Paths reaching the stream layer come from database
 * rows rather than request parameters, but a stale row, a symlink inside a root or
 * a future endpoint that does accept a path would otherwise be enough to read
 * arbitrary files off the server, so the check is enforced at the boundary instead
 * of trusted at the call sites.
 */
@Slf4j
@Component
public class MediaPaths {

    private final List<Path> roots;

    public MediaPaths(MediaProperties props) {
        List<Path> resolved = new ArrayList<>();
        for (String raw : props.getRoots()) {
            if (raw == null || raw.isBlank()) continue;
            try {
                Path root = Path.of(raw.trim()).toAbsolutePath().normalize();
                if (!Files.isDirectory(root)) {
                    log.warn("Media root does not exist or is not a directory, ignoring: {}", root);
                    continue;
                }
                resolved.add(realOrNormalized(root));
            } catch (InvalidPathException ex) {
                log.warn("Media root is not a valid path, ignoring: {}", raw);
            }
        }
        this.roots = List.copyOf(resolved);
        if (this.roots.isEmpty()) {
            log.info("No app.media.roots configured — movie library is disabled");
        } else {
            log.info("Movie library roots: {}", this.roots);
        }
    }

    public List<Path> roots() {
        return roots;
    }

    public boolean isConfigured() {
        return !roots.isEmpty();
    }

    /**
     * Resolves {@code raw} and confirms it sits inside a configured root.
     *
     * <p>Resolution goes through {@link Path#toRealPath} so that {@code ..} segments
     * and symlinks are collapsed <em>before</em> the containment test — comparing
     * un-resolved paths would let a symlink inside a root point anywhere on disk.
     *
     * @throws ApiException 404 if the file is gone, 403 if it resolves outside every root
     */
    public Path requireWithinRoots(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File not available");
        }
        Path candidate;
        try {
            candidate = Path.of(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File not available");
        }
        if (!Files.exists(candidate)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File no longer on disk");
        }
        Path real = realOrNormalized(candidate);
        for (Path root : roots) {
            if (real.startsWith(root)) {
                return real;
            }
        }
        // Logged as a warning: in normal operation this cannot happen, so it means
        // either a stale row from a since-removed root or a genuine traversal attempt.
        log.warn("Refused access to path outside media roots: {}", real);
        throw new ApiException(HttpStatus.FORBIDDEN, "Path is outside the media library");
    }

    /** {@code toRealPath} needs the file to exist; fall back to a lexical normalize. */
    private static Path realOrNormalized(Path p) {
        try {
            return p.toRealPath();
        } catch (Exception ex) {
            return p.toAbsolutePath().normalize();
        }
    }
}
