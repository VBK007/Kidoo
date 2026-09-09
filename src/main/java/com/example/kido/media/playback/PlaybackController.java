package com.example.kido.media.playback;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.example.kido.user.AppUser;

import jakarta.validation.Valid;

/** Resume points and the continue-watching row. */
@RestController
@RequestMapping("/api/media")
public class PlaybackController {

    private final PlaybackService service;

    public PlaybackController(PlaybackService service) {
        this.service = service;
    }

    /**
     * Upserts the caller's position in a title.
     *
     * <p>Idempotent by design: the client posts this every few seconds during playback
     * and again on pause and stop, and each call replaces the single stored row.
     */
    @PutMapping("/movies/{id}/progress")
    public ProgressDto record(@AuthenticationPrincipal AppUser user,
                              @PathVariable String id,
                              @Valid @RequestBody ProgressRequest request) {
        return service.record(user, id, request);
    }

    @GetMapping("/movies/{id}/progress")
    public ProgressDto get(@AuthenticationPrincipal AppUser user, @PathVariable String id) {
        return service.find(user, id).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "No progress recorded for this movie"));
    }

    /** Forgets the resume point so the title starts from the beginning. */
    @DeleteMapping("/movies/{id}/progress")
    public ResponseEntity<Void> reset(@AuthenticationPrincipal AppUser user, @PathVariable String id) {
        service.reset(user, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/continue-watching")
    public List<ContinueWatchingDto> continueWatching(@AuthenticationPrincipal AppUser user,
                                                      @RequestParam(defaultValue = "20") int limit) {
        return service.continueWatching(user, Math.min(limit, 50));
    }
}
