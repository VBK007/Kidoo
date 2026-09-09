package com.example.kido.media;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.example.kido.common.ApiException;
import com.example.kido.media.catalog.MediaType;

import lombok.extern.slf4j.Slf4j;

/**
 * The module's single gate onto the filesystem, and the record of which library each
 * root belongs to.
 *
 * <p>Every path the server opens — video, artwork, subtitle, sprite source — is
 * resolved through {@link #requireWithinRoots}. Paths reaching the stream layer come
 * from database rows rather than request parameters, but a stale row, a symlink inside
 * a root, or a future endpoint that does accept a path would each be enough to read
 * arbitrary files off the server. So the check is enforced at the boundary rather than
 * trusted at the call sites.
 */
@Slf4j
@Component
public class MediaPaths {

    private final List<LibraryRoot> roots;

    public MediaPaths(MediaProperties props) {
        List<LibraryRoot> resolved = new ArrayList<>();
        for (MediaProperties.Library library : props.effectiveLibraries()) {
            try {
                Path path = Path.of(library.getPath().trim()).toAbsolutePath().normalize();
                if (!Files.isDirectory(path)) {
                    log.warn("Media library '{}' path does not exist or is not a directory, "
                            + "ignoring: {}", library.getName(), path);
                    continue;
                }
                resolved.add(new LibraryRoot(
                        library.getName(), realOrNormalized(path), library.getType()));
            } catch (InvalidPathException ex) {
                log.warn("Media library '{}' has an invalid path, ignoring: {}",
                        library.getName(), library.getPath());
            }
        }
        this.roots = List.copyOf(resolved);

        if (this.roots.isEmpty()) {
            log.info("No media libraries configured - the library is disabled");
        } else {
            for (LibraryRoot root : this.roots) {
                log.info("Media library '{}' [{}] at {}", root.name(), root.type(), root.path());
            }
        }
    }

    public List<LibraryRoot> libraryRoots() {
        return roots;
    }

    public List<Path> roots() {
        return roots.stream().map(LibraryRoot::path).toList();
    }

    public boolean isConfigured() {
        return !roots.isEmpty();
    }

    /**
     * Resolves {@code raw} and confirms it sits inside a configured root.
     *
     * <p>Resolution goes through {@link Path#toRealPath} so {@code ..} segments and
     * symlinks are collapsed <em>before</em> the containment test — comparing
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
        for (LibraryRoot root : roots) {
            if (real.startsWith(root.path())) {
                return real;
            }
        }
        // A warning because in normal operation this cannot happen: it means either a
        // stale row from a since-removed library or a genuine traversal attempt.
        log.warn("Refused access to path outside media libraries: {}", real);
        throw new ApiException(HttpStatus.FORBIDDEN, "Path is outside the media library");
    }

    /** Which library a path belongs to, if any. */
    public Optional<LibraryRoot> libraryOf(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return roots.stream().filter(root -> normalized.startsWith(root.path())).findFirst();
    }

    /** {@code toRealPath} needs the file to exist; fall back to a lexical normalize. */
    private static Path realOrNormalized(Path path) {
        try {
            return path.toRealPath();
        } catch (Exception ex) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** A configured root and the media type everything beneath it is taken to be. */
    public record LibraryRoot(String name, Path path, MediaType type) {}
}
