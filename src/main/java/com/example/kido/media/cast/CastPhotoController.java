package com.example.kido.media.cast;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.dto.CastPhotoDtos.CastMemberDto;
import com.example.kido.media.stream.FileStreamer;
import com.example.kido.user.AppUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Cast/crew photos, fetched from TMDB and cached locally.
 *
 * <p>Decorative rather than essential to playback — unlike poster/backdrop/subtitles,
 * a watch-party guest has no need for these, so both endpoints sit under the account-
 * only default rather than joining that guest-accessible list in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/media")
public class CastPhotoController {

    /** The cached photo's bytes never change under the same URL. */
    private static final long PHOTO_CACHE_SECONDS = 86_400;

    private final CatalogService catalog;
    private final CastPhotoService photos;
    private final FileStreamer streamer;

    public CastPhotoController(CatalogService catalog, CastPhotoService photos, FileStreamer streamer) {
        this.catalog = catalog;
        this.photos = photos;
        this.streamer = streamer;
    }

    /** A movie's cast, each with a photo URL where one could be resolved. */
    @GetMapping("/items/{id}/cast")
    public List<CastMemberDto> cast(@AuthenticationPrincipal AppUser user, @PathVariable String id) {
        MediaItem item = catalog.require(id);
        List<String> names = splitNames(item.getCastMembers());
        List<CastMemberDto> result = new ArrayList<>();
        for (String name : names) {
            CastPhoto photo = photos.photoFor(name);
            result.add(photo == null ? CastMemberDto.unresolved(name) : CastMemberDto.from(photo));
        }
        return result;
    }

    @GetMapping("/cast/{slug}/photo")
    public void photo(@AuthenticationPrincipal AppUser user,
                      @PathVariable String slug,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        Path file = photos.fileFor(slug);
        streamer.serve(file, "image/jpeg", PHOTO_CACHE_SECONDS, request, response);
    }

    private static List<String> splitNames(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return List.of(joined.split("\\s*,\\s*"));
    }
}
