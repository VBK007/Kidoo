package com.example.kido.media.engagement.mirror;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.engagement.mirror.MirrorProgressDtos.ProgressItemDto;
import com.example.kido.media.engagement.mirror.MirrorProgressDtos.ProgressPageDto;
import com.example.kido.media.engagement.mirror.MirrorProgressDtos.ProgressRequest;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;

import jakarta.validation.Valid;

/**
 * Continue Watching, for mirror titles.
 *
 * <p>Sibling to {@link MirrorSavedController}, and laid out the same way: scoped to the
 * {@code X-Profile-Id} profile so a household resumes each to its own place, keyed on the
 * external {@code mirrorId} that never names a {@code media_items} row, with the rail one
 * level up at {@code /progress} where no id in the path can collide with a title's
 * {@code /{mirrorId}/progress}.
 *
 * <p>One verb differs in spirit. {@code PUT} here is not a toggle to a known state but the
 * player writing where it has got to — sent on its heartbeat and once more at close — so
 * the body carries a position, and the same title {@code PUT} twice moves the mark rather
 * than leaving it. {@code DELETE} is the viewer swiping a title off the rail.
 */
@RestController
@RequestMapping("/api/media/mirror")
public class MirrorProgressController {

    private final MirrorProgressService service;

    public MirrorProgressController(MirrorProgressService service) {
        this.service = service;
    }

    /** Record where the player got to — insert on first watch, move the mark after. */
    @PutMapping("/{mirrorId}/progress")
    public ProgressItemDto record(@ActiveProfile Profile profile,
                                  @PathVariable String mirrorId,
                                  @Valid @RequestBody ProgressRequest request) {
        return service.record(profile, mirrorId, request);
    }

    /**
     * Where this profile left this one title, so the player can seek on open. 200 with the
     * row when there is a resume point, 204 when there is none (never watched, or finished
     * and so started over) — the client reads an empty body as "from the top".
     */
    @GetMapping("/{mirrorId}/progress")
    public ResponseEntity<ProgressItemDto> find(@ActiveProfile Profile profile,
                                                @PathVariable String mirrorId) {
        return service.find(profile, mirrorId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Swipe a title off the rail. Idempotent — removing one already gone is a no-op. */
    @DeleteMapping("/{mirrorId}/progress")
    public void remove(@ActiveProfile Profile profile, @PathVariable String mirrorId) {
        service.remove(profile, mirrorId);
    }

    /** This profile's rail, most recently watched first. Default page size 30, capped at 100. */
    @GetMapping("/progress")
    public ProgressPageDto list(@ActiveProfile Profile profile,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "30") int size) {
        return service.list(profile, page, size);
    }
}
