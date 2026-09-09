package com.example.kido.media.trickplay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.common.ApiException;
import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.dto.PlayerDtos.ChapterDto;
import com.example.kido.media.dto.PlayerDtos.PlayerStateDto;
import com.example.kido.media.dto.PlayerDtos.TrickplayDto;
import com.example.kido.media.playback.PlaybackProgress;
import com.example.kido.media.playback.PlaybackService;
import com.example.kido.media.probe.MediaChapterRepository;
import com.example.kido.media.stream.FileStreamer;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Player-screen data: chapter markers, thumbnail-scrub sprite sheets, and the combined
 * state the player restores when it opens.
 */
@RestController
@RequestMapping("/api/media/items/{id}")
public class PlayerController {

    /** Sheets are immutable once generated, so they cache hard. */
    private static final long SHEET_CACHE_SECONDS = 604_800;

    private final CatalogService catalog;
    private final TrickplayService trickplay;
    private final MediaChapterRepository chapters;
    private final PlaybackService playback;
    private final FileStreamer streamer;

    public PlayerController(CatalogService catalog,
                            TrickplayService trickplay,
                            MediaChapterRepository chapters,
                            PlaybackService playback,
                            FileStreamer streamer) {
        this.catalog = catalog;
        this.trickplay = trickplay;
        this.chapters = chapters;
        this.playback = playback;
        this.streamer = streamer;
    }

    /** Chapter markers for the scrubber ticks; empty when the file declares none. */
    @GetMapping("/chapters")
    public List<ChapterDto> chapters(@PathVariable String id) {
        catalog.require(id);
        return chapters.findByMediaItemIdOrderByChapterIndexAsc(id).stream()
                .map(ChapterDto::from)
                .toList();
    }

    /**
     * The sprite-sheet manifest for thumbnail scrubbing.
     *
     * <p>404 until frames have been generated. The client should fall back to a plain
     * scrubber rather than treating that as an error.
     */
    @GetMapping("/trickplay")
    public TrickplayDto trickplay(@PathVariable String id) {
        catalog.require(id);
        return trickplay.find(id)
                .map(manifest -> TrickplayDto.from(manifest, id))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No thumbnail frames generated for this item"));
    }

    /**
     * Starts sprite-sheet generation and returns immediately.
     *
     * <p>Generation decodes the whole file, so the response is 202 with a
     * {@code GENERATING} manifest the client can poll, or 200 if frames already exist.
     */
    @PostMapping("/trickplay")
    public ResponseEntity<TrickplayDto> generateTrickplay(@PathVariable String id) {
        MediaItem item = catalog.require(id);
        TrickplayManifest manifest = trickplay.requestGeneration(item);
        HttpStatus status = manifest.isReady() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(TrickplayDto.from(manifest, id));
    }

    @DeleteMapping("/trickplay")
    public ResponseEntity<Void> discardTrickplay(@PathVariable String id) {
        trickplay.discard(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * One sprite sheet as JPEG.
     *
     * @param sheet name taken from the manifest URL template; pattern-validated before
     *              being resolved against the cache directory for this item
     */
    @GetMapping("/trickplay/{sheet}")
    public void sheet(@PathVariable String id,
                      @PathVariable String sheet,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        catalog.require(id);
        Path file = trickplay.sheetFile(id, sheet);
        streamer.serve(file, MediaType.IMAGE_JPEG_VALUE, SHEET_CACHE_SECONDS, request, response);
    }

    /**
     * Everything the player needs on open, in one request: resume position, remembered
     * tracks, subtitle offset, chapters and the trickplay manifest.
     *
     * <p>One round trip rather than four, because the player opens on a tap and every
     * extra request shows up as delay before the first frame.
     */
    @GetMapping("/player-state")
    public PlayerStateDto playerState(@ActiveProfile Profile profile, @PathVariable String id) {
        catalog.require(id);
        PlaybackProgress progress = playback.findEntity(profile, id).orElse(null);

        return new PlayerStateDto(
                id,
                progress == null ? 0 : progress.getPositionSeconds(),
                progress == null ? null : progress.getDurationSeconds(),
                progress != null && progress.isWatched(),
                progress == null ? null : progress.getSubtitleOffsetSeconds(),
                progress == null ? null : progress.getSubtitleTrackIndex(),
                progress == null ? null : progress.getAudioTrackIndex(),
                chapters.findByMediaItemIdOrderByChapterIndexAsc(id).stream()
                        .map(ChapterDto::from)
                        .toList(),
                trickplay.find(id).map(manifest -> TrickplayDto.from(manifest, id)).orElse(null));
    }
}
