package com.example.kido.media.matchfix;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.example.kido.media.dto.MatchFixDtos.ApplyMatchRequest;
import com.example.kido.media.dto.MatchFixDtos.FixResultDto;
import com.example.kido.media.dto.MatchFixDtos.FixScreenDto;
import com.example.kido.media.dto.MatchFixDtos.MismatchQueueDto;
import com.example.kido.media.dto.MatchFixDtos.ReclassifyRequest;
import com.example.kido.user.AppUser;
import com.example.kido.user.Role;

import jakarta.validation.Valid;

/**
 * The fix-wrong-metadata screen.
 *
 * <p>Owner-only, gated the same way as the admin panel: the admin key plus a
 * {@code PARENT} account. Two reasons rather than one. It rewrites what every household
 * member sees, and it is the only part of the API that returns real filesystem paths —
 * which it does because identifying a file is precisely what the screen asks the owner
 * to do, and the folder is usually the best clue available.
 *
 * <p>No endpoint here writes to the disk. Corrections change database rows; the file
 * keeps its name and its place.
 */
@RestController
@RequestMapping("/api/media/admin/matches")
public class MatchFixController {

    private final MatchFixService service;
    private final String adminKey;

    public MatchFixController(MatchFixService service,
                             @Value("${app.admin.api-key}") String adminKey) {
        this.service = service;
        this.adminKey = adminKey;
    }

    /** The queue behind "Fix wrong matches · N titles". */
    @GetMapping
    public MismatchQueueDto queue(@AuthenticationPrincipal AppUser user,
                                  @RequestHeader(value = "X-Admin-Key", required = false) String key,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        requireOwner(user, key);
        return service.queue(page, size);
    }

    /**
     * Everything the screen renders for one item: the real path, the current guess, and
     * ranked candidates.
     *
     * @param q optional search text; without it, candidates are suggested from the file
     */
    @GetMapping("/{id}")
    public FixScreenDto fixScreen(@AuthenticationPrincipal AppUser user,
                                  @RequestHeader(value = "X-Admin-Key", required = false) String key,
                                  @PathVariable String id,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "10") int limit) {
        requireOwner(user, key);
        return service.fixScreen(id, q, limit);
    }

    /**
     * Applies the correction and locks it, so the next rescan cannot undo it.
     *
     * <p>Takes either a {@code candidateId} from the last response or a title typed
     * directly.
     */
    @PutMapping("/{id}")
    public FixResultDto apply(@AuthenticationPrincipal AppUser user,
                              @RequestHeader(value = "X-Admin-Key", required = false) String key,
                              @PathVariable String id,
                              @Valid @RequestBody ApplyMatchRequest request) {
        requireOwner(user, key);
        return service.apply(id, request);
    }

    /** "It's a home video, not a film" — moves the category, never the file. */
    @PostMapping("/{id}/reclassify")
    public FixResultDto reclassify(
            @AuthenticationPrincipal AppUser user,
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable String id,
            @Valid @RequestBody ReclassifyRequest request) {
        requireOwner(user, key);
        return service.reclassify(id, request.type());
    }

    /** "Leave it unmatched" — hides it from browsing and from this queue. */
    @PostMapping("/{id}/unmatch")
    public FixResultDto unmatch(@AuthenticationPrincipal AppUser user,
                                @RequestHeader(value = "X-Admin-Key", required = false) String key,
                                @PathVariable String id) {
        requireOwner(user, key);
        return service.unmatch(id);
    }

    /** Undoes a correction and lets the next scan read the file again. */
    @PostMapping("/{id}/reset")
    public FixResultDto reset(@AuthenticationPrincipal AppUser user,
                              @RequestHeader(value = "X-Admin-Key", required = false) String key,
                              @PathVariable String id) {
        requireOwner(user, key);
        return service.reset(id);
    }

    /**
     * Requires both the admin key and a parent account, returning 403 without saying
     * which check failed so the response cannot confirm a guessed key.
     */
    private void requireOwner(AppUser user, String key) {
        boolean keyOk = adminKey != null && !adminKey.isBlank() && adminKey.equals(key);
        boolean roleOk = user != null && user.getRole() == Role.PARENT;
        if (!keyOk || !roleOk) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Fixing metadata is owner-only and requires a valid admin key");
        }
    }
}
