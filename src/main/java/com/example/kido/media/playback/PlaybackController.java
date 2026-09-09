package com.example.kido.media.playback;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.common.ApiException;
import com.example.kido.media.dto.PlaybackDtos.ContinueWatchingDto;
import com.example.kido.media.dto.PlaybackDtos.ProgressDto;
import com.example.kido.media.dto.PlaybackDtos.ProgressRequest;
import com.example.kido.media.dto.PlayerDtos.SubtitleOffsetRequest;
import com.example.kido.media.dto.PlayerDtos.TrackSelectionRequest;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;

import jakarta.validation.Valid;

/** Resume points, track choices and the continue-watching row, all scoped to a profile. */
@RestController
@RequestMapping("/api/media")
public class PlaybackController {

    private final PlaybackService service;

    public PlaybackController(PlaybackService service) {
        this.service = service;
    }

    /**
     * Upserts the profile's position in a title.
     *
     * <p>Idempotent by design: the client posts this every few seconds during playback
     * and again on pause and stop, and each call replaces the single stored row.
     */
    @PutMapping("/items/{id}/progress")
    public ProgressDto record(@ActiveProfile Profile profile,
                              @PathVariable String id,
                              @Valid @RequestBody ProgressRequest request) {
        return service.record(profile, id, request);
    }

    @GetMapping("/items/{id}/progress")
    public ProgressDto get(@ActiveProfile Profile profile, @PathVariable String id) {
        return service.find(profile, id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "No progress recorded for this item"));
    }

    /** Forgets the resume point so the title starts from the beginning. */
    @DeleteMapping("/items/{id}/progress")
    public ResponseEntity<Void> reset(@ActiveProfile Profile profile, @PathVariable String id) {
        service.reset(profile, id);
        return ResponseEntity.noContent().build();
    }

    /** Subtitle timing correction, saved per profile and file. */
    @PutMapping("/items/{id}/subtitle-offset")
    public ProgressDto setSubtitleOffset(@ActiveProfile Profile profile,
                                         @PathVariable String id,
                                         @Valid @RequestBody SubtitleOffsetRequest request) {
        return service.setSubtitleOffset(profile, id, request);
    }

    /** Remembers the chosen subtitle and audio tracks for next time. */
    @PutMapping("/items/{id}/tracks")
    public ProgressDto setTracks(@ActiveProfile Profile profile,
                                 @PathVariable String id,
                                 @Valid @RequestBody TrackSelectionRequest request) {
        return service.setTracks(profile, id, request);
    }

    @GetMapping("/continue-watching")
    public List<ContinueWatchingDto> continueWatching(
            @ActiveProfile Profile profile,
            @RequestParam(defaultValue = "20") int limit) {
        return service.continueWatching(profile, Math.min(limit, 50));
    }
}
