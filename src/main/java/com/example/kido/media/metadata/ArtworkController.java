package com.example.kido.media.metadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.example.kido.user.AppUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves sidecar artwork and subtitles by item id.
 *
 * <p>Filesystem paths are never exposed to or accepted from the client: the poster for
 * an item is addressed as {@code /movies/{id}/poster} and the path is looked up from
 * the row, then re-validated against the media roots. Subtitles are addressed by the
 * positional index the detail response handed out.
 */
@RestController
@RequestMapping("/api/media/items/{id}")
public class ArtworkController {

    /** Artwork is effectively immutable, and re-fetching it on every scroll is wasteful. */
    private static final long ARTWORK_CACHE_SECONDS = 86_400;

    private final CatalogService catalog;
    private final MediaPaths paths;
    private final FileStreamer streamer;
    private final SubtitleConverter subtitles;

    public ArtworkController(CatalogService catalog,
                             MediaPaths paths,
                             FileStreamer streamer,
                             SubtitleConverter subtitles) {
        this.catalog = catalog;
        this.paths = paths;
        this.streamer = streamer;
        this.subtitles = subtitles;
    }

    @GetMapping("/poster")
    public void poster(@AuthenticationPrincipal AppUser user,
                       @PathVariable String id,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        MediaItem item = catalog.require(id);
        serveImage(item.getPosterPath(), "poster", request, response);
    }

    @GetMapping("/backdrop")
    public void backdrop(@AuthenticationPrincipal AppUser user,
                         @PathVariable String id,
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
        Path file = paths.requireWithinRoots(storedPath);
        streamer.serve(file, imageContentType(file), ARTWORK_CACHE_SECONDS, request, response);
    }

    /**
     * External subtitle track as WebVTT.
     *
     * <p>{@code index} is the position from the detail response. Embedded tracks are
     * listed there too but are not served here — extracting them needs an ffmpeg pass
     * that is not wired up yet, so requesting one is an explicit 501 rather than a
     * confusing 404.
     */
    @GetMapping("/subtitles/{index}")
    public ResponseEntity<byte[]> subtitle(@AuthenticationPrincipal AppUser user,
                                           @PathVariable String id,
                                           @PathVariable int index) throws IOException {
        MediaItem item = catalog.require(id);
        List<SidecarLocator.SubtitleTrack> tracks = catalog.externalSubtitles(item);

        if (index < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Subtitle index must not be negative");
        }
        if (index >= tracks.size()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
                    "Only external subtitle files can be served; embedded tracks need "
                            + "extraction, which is not implemented");
        }

        SidecarLocator.SubtitleTrack track = tracks.get(index);
        Path file = paths.requireWithinRoots(track.path());
        String vtt = subtitles.toWebVtt(file, track.format());

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "vtt", StandardCharsets.UTF_8))
                .cacheControl(org.springframework.http.CacheControl
                        .maxAge(java.time.Duration.ofSeconds(ARTWORK_CACHE_SECONDS))
                        .cachePrivate())
                .body(vtt.getBytes(StandardCharsets.UTF_8));
    }

    /** Derived from the extension; artwork is only ever one of a few image types. */
    private static String imageContentType(Path file) {
        String extension = MediaFiles.extension(file.getFileName().toString());
        return switch (extension) {
            case "png" -> MediaType.IMAGE_PNG_VALUE;
            case "webp" -> "image/webp";
            case "gif" -> MediaType.IMAGE_GIF_VALUE;
            default -> MediaType.IMAGE_JPEG_VALUE;
        };
    }
}
