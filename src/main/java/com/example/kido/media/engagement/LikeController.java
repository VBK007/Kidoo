package com.example.kido.media.engagement;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.dto.EngagementDtos.LikeDto;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;

/**
 * The heart on a poster. Scoped to the profile in {@code X-Profile-Id}, so each member
 * of a household likes for themselves.
 *
 * <p>{@code PUT}/{@code DELETE} rather than a {@code POST /toggle}: the client knows
 * which state it wants, and a toggle sent twice on a flaky connection lands back where
 * it started. Both verbs are idempotent and return the same shape.
 */
@RestController
@RequestMapping("/api/media/items/{id}/like")
public class LikeController {

    private final LikeService service;

    public LikeController(LikeService service) {
        this.service = service;
    }

    @PutMapping
    public LikeDto like(@ActiveProfile Profile profile, @PathVariable String id) {
        return service.like(profile, id);
    }

    @DeleteMapping
    public LikeDto unlike(@ActiveProfile Profile profile, @PathVariable String id) {
        return service.unlike(profile, id);
    }

    @GetMapping
    public LikeDto status(@ActiveProfile Profile profile, @PathVariable String id) {
        return service.status(profile, id);
    }
}
