package com.example.kido.media.admin;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.common.ApiException;
import com.example.kido.media.dto.AdminDtos.DiskTabDto;
import com.example.kido.media.dto.AdminDtos.HealthTabDto;
import com.example.kido.media.dto.AdminDtos.PeopleTabDto;
import com.example.kido.media.dto.AdminDtos.PurgeResultDto;
import com.example.kido.media.dto.AdminDtos.SessionDto;
import com.example.kido.user.AppUser;
import com.example.kido.user.Role;

/**
 * The owner-only admin panel: Health, People and Disk.
 *
 * <p>Gated twice over. The caller must hold the {@code X-Admin-Key} — the same header
 * the content-publishing and library-scan endpoints already use — <em>and</em> be a
 * {@code PARENT} rather than a child profile's account. The key alone would let a
 * child's device act as owner if the key ever leaked into a shared client build; the
 * role alone would let any parent in a shared household end other people's streams.
 *
 * <p>Everything here is read-only apart from ending a stream and purging caches, both
 * of which are explicit owner actions.
 */
@RestController
@RequestMapping("/api/media/admin")
public class AdminController {

    private final AdminService admin;
    private final DiskService disk;
    private final String adminKey;

    public AdminController(AdminService admin,
                           DiskService disk,
                           @Value("${app.admin.api-key}") String adminKey) {
        this.admin = admin;
        this.disk = disk;
        this.adminKey = adminKey;
    }

    // --- Health tab ---

    @GetMapping("/health")
    public HealthTabDto health(@AuthenticationPrincipal AppUser user,
                               @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        requireOwner(user, key);
        return admin.healthTab();
    }

    // --- People tab ---

    @GetMapping("/people")
    public PeopleTabDto people(@AuthenticationPrincipal AppUser user,
                               @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        requireOwner(user, key);
        return admin.peopleTab();
    }

    /** Live streams on their own, for polling without refetching the whole tab. */
    @GetMapping("/sessions")
    public List<SessionDto> sessions(
            @AuthenticationPrincipal AppUser user,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        requireOwner(user, key);
        return admin.liveSessions();
    }

    /**
     * Ends a live stream.
     *
     * <p>A transcode stops immediately, since its ffmpeg process is killed. A direct
     * play stops on its next range request — there is no process to kill and the
     * response already in flight cannot be recalled — which for a player pulling every
     * few seconds is effectively immediate.
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> endSession(
            @AuthenticationPrincipal AppUser user,
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable String sessionId) {
        requireOwner(user, key);
        if (!admin.endSession(sessionId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No such session");
        }
        return ResponseEntity.noContent().build();
    }

    // --- Disk tab ---

    @GetMapping("/disk")
    public DiskTabDto disk(@AuthenticationPrincipal AppUser user,
                           @RequestHeader(value = "X-Admin-Key", required = false) String key,
                           @RequestParam(defaultValue = "20") int biggestFiles) {
        requireOwner(user, key);
        return disk.diskTab(biggestFiles);
    }

    /**
     * Deletes the transcode and thumbnail scratch caches.
     *
     * <p>Safe by construction: both are regenerated on demand. This does not touch
     * anything in the media libraries, and no endpoint here ever deletes a media file —
     * removing someone's footage is not something an API call should be able to do.
     */
    @DeleteMapping("/disk/caches")
    public PurgeResultDto purgeCaches(
            @AuthenticationPrincipal AppUser user,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        requireOwner(user, key);
        return disk.purgeCaches();
    }

    /**
     * Requires both the admin key and a parent account.
     *
     * <p>Returns 403 without distinguishing which check failed, so the response cannot
     * be used to confirm a guessed key.
     */
    private void requireOwner(AppUser user, String key) {
        boolean keyOk = adminKey != null && !adminKey.isBlank() && adminKey.equals(key);
        boolean roleOk = user != null && user.getRole() == Role.PARENT;
        if (!keyOk || !roleOk) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "The admin panel is owner-only and requires a valid admin key");
        }
    }
}
