package com.example.kido.media.engagement.mirror;

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
 * The heart on a mirror poster.
 *
 * <p>The mirror counterpart to {@code LikeController}: identical verbs, identical
 * {@link LikeDto} shape, scoped to the profile in {@code X-Profile-Id} so each member of a
 * household likes for themselves. The only difference is the path — it keys on a
 * {@code mirrorId}, the external catalogue token a mirror title carries, which never names
 * a {@code media_items} row. Those ids are bare tokens (a Netflix/Hotstar number, a Prime
 * ASIN) with no slash, so they sit in the path exactly as a catalogue id does.
 *
 * <p>{@code PUT}/{@code DELETE} rather than a {@code POST /toggle}, for the reason the
 * catalogue heart uses them: the client knows the state it wants, and a toggle sent twice
 * on a flaky connection lands back where it started. Both verbs are idempotent.
 */
@RestController
@RequestMapping("/api/media/mirror/{mirrorId}/like")
public class MirrorLikeController {

    private final MirrorLikeService service;

    public MirrorLikeController(MirrorLikeService service) {
        this.service = service;
    }

    @PutMapping
    public LikeDto like(@ActiveProfile Profile profile, @PathVariable String mirrorId) {
        return service.like(profile, mirrorId);
    }

    @DeleteMapping
    public LikeDto unlike(@ActiveProfile Profile profile, @PathVariable String mirrorId) {
        return service.unlike(profile, mirrorId);
    }

    @GetMapping
    public LikeDto status(@ActiveProfile Profile profile, @PathVariable String mirrorId) {
        return service.status(profile, mirrorId);
    }
}
