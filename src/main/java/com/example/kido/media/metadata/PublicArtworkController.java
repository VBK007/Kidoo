package com.example.kido.media.metadata;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaFiles;
import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.stream.FileStreamer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Poster and backdrop images for a visitor with no account yet.
 *
 * <p>Kept separate from {@link ArtworkController} rather than folded in behind an
 * optional-auth check: that controller's routes are reachable by watch-party guests
 * for the one film they were admitted to, which is a narrower grant than "anyone with
 * the server's address can see this poster." Serving logic is duplicated rather than
 * shared on purpose — a security-sensitive public route and an authenticated one
 * should not depend on the same method staying correct for both as either evolves.
 */
@RestController
@RequestMapping("/api/media/public/items/{id}")
public class PublicArtworkController {

    private static final long ARTWORK_CACHE_SECONDS = 86_400;

    private final CatalogService catalog;
    private final MediaPaths paths;
    private final FileStreamer streamer;

    public PublicArtworkController(CatalogService catalog, MediaPaths paths, FileStreamer streamer) {
        this.catalog = catalog;
        this.paths = paths;
        this.streamer = streamer;
    }

    @GetMapping("/poster")
    public void poster(@PathVariable String id,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        MediaItem item = catalog.require(id);
        serveImage(item.getPosterPath(), "poster", request, response);
    }

    @GetMapping("/backdrop")
    public void backdrop(@PathVariable String id,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        MediaItem item = catalog.require(id);
        serveImage(item.getBackdropPath(), "backdrop", request, response);
    }

    private void serveImage(String storedPath, String what,
                            HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (storedPath == null || storedPath.isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No " + what + " for this item");
        }
        Path file = paths.requireImage(storedPath);
        streamer.serve(file, imageContentType(file), ARTWORK_CACHE_SECONDS, true, request, response);
    }

    private static String imageContentType(Path file) {
        String extension = MediaFiles.extension(file.getFileName().toString());
        return switch (extension) {
            case "png" -> MediaType.IMAGE_PNG_VALUE;
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "gif" -> MediaType.IMAGE_GIF_VALUE;
            default -> MediaType.IMAGE_JPEG_VALUE;
        };
    }
}
