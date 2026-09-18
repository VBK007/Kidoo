package com.example.kido.media.teasers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaFiles;
import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.dto.TeaserClipDtos.CreateTeaserClipRequest;
import com.example.kido.media.dto.TeaserClipDtos.PublishRequest;
import com.example.kido.media.dto.TeaserClipDtos.TeaserClipDto;
import com.example.kido.media.dto.TeaserClipDtos.TeaserFeedPageDto;
import com.example.kido.media.stream.FileStreamer;
import com.example.kido.media.together.WatchPartyGrants;
import com.example.kido.security.GuestPrincipal;
import com.example.kido.user.AppUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Teaser shorts: curated vertical clips cut from a movie's own file, used to hook
 * viewers into watching the full thing.
 *
 * <p>Creating, publishing and deleting a clip is an expensive, deliberate operation —
 * an ffmpeg encode, and a decision about what a household sees — so those are gated on
 * the same {@code X-Admin-Key} header the library scan and content-publishing endpoints
 * use. Reading published clips is open to any account, and to a watch-party guest for
 * the item they were admitted to, the same as poster/backdrop/stream.
 */
@RestController
@RequestMapping("/api/media")
public class TeaserClipController {

    private final TeaserClipService teasers;
    private final CatalogService catalog;
    private final FileStreamer streamer;
    private final WatchPartyGrants grants;
    private final String adminKey;

    public TeaserClipController(TeaserClipService teasers,
                                CatalogService catalog,
                                FileStreamer streamer,
                                WatchPartyGrants grants,
                                @Value("${app.admin.api-key}") String adminKey) {
        this.teasers = teasers;
        this.catalog = catalog;
        this.streamer = streamer;
        this.grants = grants;
        this.adminKey = adminKey;
    }

    /** Queues a new clip. Always 202 — even a fast encode is not worth blocking for. */
    @PostMapping("/items/{id}/teasers")
    public ResponseEntity<TeaserClipDto> create(
            @AuthenticationPrincipal AppUser user,
            @PathVariable String id,
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @Valid @RequestBody CreateTeaserClipRequest request) {

        requireAdmin(key);
        MediaItem item = catalog.require(id);
        TeaserClip clip = teasers.requestGeneration(user, item,
                request.startSeconds(), request.endSeconds(), request.horizontalOffset(),
                request.label(), request.sourceNote(), request.published());
        return ResponseEntity.accepted().body(TeaserClipDto.from(clip, item));
    }

    /** Published, ready clips for a movie — what the app shows on its detail screen. */
    @GetMapping("/items/{id}/teasers")
    public List<TeaserClipDto> published(
            @AuthenticationPrincipal GuestPrincipal guest,
            @PathVariable String id) {

        grants.requirePlayable(guest, id);
        MediaItem item = catalog.require(id);
        return teasers.publishedFor(item.getId()).stream()
                .map(clip -> TeaserClipDto.from(clip, item))
                .toList();
    }

    /** Every clip for a movie, any state — the admin review list. */
    @GetMapping("/items/{id}/teasers/all")
    public List<TeaserClipDto> all(
            @AuthenticationPrincipal AppUser user,
            @PathVariable String id,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {

        requireAdmin(key);
        MediaItem item = catalog.require(id);
        return teasers.allFor(item.getId()).stream()
                .map(clip -> TeaserClipDto.from(clip, item))
                .toList();
    }

    /** Polled while a clip is generating, for its status. */
    @GetMapping("/items/{id}/teasers/{clipId}")
    public TeaserClipDto status(
            @AuthenticationPrincipal AppUser user,
            @PathVariable String id,
            @PathVariable String clipId,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {

        requireAdmin(key);
        MediaItem item = catalog.require(id);
        return TeaserClipDto.from(teasers.requireForItem(id, clipId), item);
    }

    @PutMapping("/items/{id}/teasers/{clipId}/publish")
    public TeaserClipDto publish(
            @AuthenticationPrincipal AppUser user,
            @PathVariable String id,
            @PathVariable String clipId,
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @Valid @RequestBody PublishRequest request) {

        requireAdmin(key);
        MediaItem item = catalog.require(id);
        return TeaserClipDto.from(teasers.setPublished(id, clipId, request.published()), item);
    }

    @DeleteMapping("/items/{id}/teasers/{clipId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AppUser user,
            @PathVariable String id,
            @PathVariable String clipId,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {

        requireAdmin(key);
        teasers.delete(id, clipId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Serves the generated clip. Range-capable through the same streamer playback
     * uses, so scrubbing a short clip costs one small request like a full movie does.
     */
    @GetMapping("/items/{id}/teasers/{clipId}/file")
    public void file(
            @AuthenticationPrincipal GuestPrincipal guest,
            @PathVariable String id,
            @PathVariable String clipId,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        grants.requirePlayable(guest, id);
        Path file = teasers.fileFor(id, clipId);
        // The bytes never change under the same URL once a clip is READY.
        streamer.serve(file, MediaFiles.contentType(file.getFileName().toString()), 86_400, request, response);
    }

    /**
     * The global discovery feed: published clips across every movie, shuffled.
     *
     * <p>Every response carries the {@code seed} its order came from. Send it back on
     * page 1, 2, 3 and so on to keep scrolling the same shuffle; omit it and the server
     * deals a new one, which is what a client wants when the viewer pulls to refresh and
     * nothing else.
     */
    @GetMapping("/teasers")
    public TeaserFeedPageDto feed(
            @AuthenticationPrincipal AppUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer seed) {

        TeaserClipService.FeedPage result = teasers.feed(page, size, seed);
        List<TeaserClipDto> items = result.clips().stream()
                .map(clip -> TeaserClipDto.from(clip, catalog.find(clip.getMediaItemId()).orElse(null)))
                .toList();
        return new TeaserFeedPageDto(
                items, result.seed(), result.page(), result.size(),
                result.totalItems(), result.totalPages());
    }

    private void requireAdmin(String key) {
        if (adminKey == null || adminKey.isBlank() || !adminKey.equals(key)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Admin key required");
        }
    }
}
