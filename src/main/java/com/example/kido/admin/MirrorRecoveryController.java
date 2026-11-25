package com.example.kido.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.mymirror.SessionRecoveryCoordinator;
import com.example.kido.user.AppUser;

@RestController
@RequestMapping("/api/admin/mirror/recovery")
public class MirrorRecoveryController {
    private final AdminAccess access;
    private final SessionRecoveryCoordinator recovery;
    private final MirrorOperations operations;

    public MirrorRecoveryController(AdminAccess access, SessionRecoveryCoordinator recovery, MirrorOperations operations) {
        this.access = access;
        this.recovery = recovery;
        this.operations = operations;
    }

    @GetMapping
    public SessionRecoveryCoordinator.Status status(@AuthenticationPrincipal AppUser user,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        access.require(user, key);
        return recovery.status();
    }

    @GetMapping("/overview")
    public MirrorOperations.Snapshot overview(@AuthenticationPrincipal AppUser user,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        access.require(user, key);
        return operations.snapshot();
    }

    @PostMapping("/retry")
    public ResponseEntity<SessionRecoveryCoordinator.Status> retry(@AuthenticationPrincipal AppUser user,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        access.require(user, key);
        return ResponseEntity.status(recovery.retry() ? 202 : 409).body(recovery.status());
    }

    @PostMapping("/cancel")
    public ResponseEntity<SessionRecoveryCoordinator.Status> cancel(@AuthenticationPrincipal AppUser user,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        access.require(user, key);
        return ResponseEntity.status(recovery.cancelAcquisition() ? 202 : 409).body(recovery.status());
    }
}
