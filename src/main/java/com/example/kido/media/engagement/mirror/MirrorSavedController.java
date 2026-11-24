package com.example.kido.media.engagement.mirror;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.engagement.mirror.MirrorSavedDtos.SaveRequest;
import com.example.kido.media.engagement.mirror.MirrorSavedDtos.SavedPageDto;
import com.example.kido.media.engagement.mirror.MirrorSavedDtos.SavedStatusDto;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;

import jakarta.validation.Valid;

/**
 * My List, for mirror titles.
 *
 * <p>The bookmark on a mirror poster and the rail it fills. Scoped to the profile in
 * {@code X-Profile-Id} so each member of a household keeps their own list. It keys on a
 * {@code mirrorId}, the external catalogue token a mirror title carries, which never names
 * a {@code media_items} row — the same token the mirror heart and thread key on.
 *
 * <p>{@code PUT}/{@code DELETE} rather than a toggle, for the reason the mirror heart uses
 * them: the client knows the state it wants, and a request sent twice on a flaky
 * connection lands where it meant to. Both are idempotent. The list lives one level up at
 * {@code /saved} with no id in the path, which cannot be confused with a title's
 * {@code /{mirrorId}/saved} because the two sit at different depths.
 */
@RestController
@RequestMapping("/api/media/mirror")
public class MirrorSavedController {

    private final MirrorSavedService service;

    public MirrorSavedController(MirrorSavedService service) {
        this.service = service;
    }

    /** Put the title on this profile's list, with the snapshot the list draws from. */
    @PutMapping("/{mirrorId}/saved")
    public SavedStatusDto save(@ActiveProfile Profile profile,
                               @PathVariable String mirrorId,
                               @Valid @RequestBody SaveRequest request) {
        return service.save(profile, mirrorId, request);
    }

    @DeleteMapping("/{mirrorId}/saved")
    public SavedStatusDto remove(@ActiveProfile Profile profile, @PathVariable String mirrorId) {
        return service.remove(profile, mirrorId);
    }

    @GetMapping("/{mirrorId}/saved")
    public SavedStatusDto status(@ActiveProfile Profile profile, @PathVariable String mirrorId) {
        return service.status(profile, mirrorId);
    }

    /** This profile's whole list, newest first. Default page size 30, capped at 100. */
    @GetMapping("/saved")
    public SavedPageDto list(@ActiveProfile Profile profile,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "30") int size) {
        return service.list(profile, page, size);
    }
}
