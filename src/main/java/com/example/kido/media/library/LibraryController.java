package com.example.kido.media.library;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.dto.LibraryDtos.ScanStatusDto;
import com.example.kido.media.dto.PlaybackDtos.TranscodeSessionDto;
import com.example.kido.media.stream.TranscodeSessionManager;
import com.example.kido.user.AppUser;

/**
 * Library administration.
 *
 * <p>Triggering a scan walks the whole disk and spawns an ffprobe process per new file,
 * so it is gated on the same {@code X-Admin-Key} header the content-publishing endpoint
 * uses. Reading status is not gated beyond the normal JWT — the app polls it to show
 * scan progress.
 */
@RestController
@RequestMapping("/api/media/library")
public class LibraryController {

    private final LibraryScanner scanner;
    private final CatalogService catalog;
    private final MediaPaths paths;
    private final TranscodeSessionManager transcodes;
    private final String adminKey;

    public LibraryController(LibraryScanner scanner,
                             CatalogService catalog,
                             MediaPaths paths,
                             TranscodeSessionManager transcodes,
                             @Value("${app.admin.api-key}") String adminKey) {
        this.scanner = scanner;
        this.catalog = catalog;
        this.paths = paths;
        this.transcodes = transcodes;
        this.adminKey = adminKey;
    }

    /** Starts a scan in the background and returns the initial status immediately. */
    @PostMapping("/scan")
    public ResponseEntity<ScanStatusDto> scan(
            @AuthenticationPrincipal AppUser user,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {

        requireAdmin(key);
        scanner.startAsync();
        return ResponseEntity.accepted().body(status(user));
    }

    @GetMapping("/status")
    public ScanStatusDto status(@AuthenticationPrincipal AppUser user) {
        return ScanStatusDto.from(scanner.status(), catalog.count(), paths.roots().size());
    }

    /** Running transcodes — the thing to check when the server feels slow. */
    @GetMapping("/transcode-sessions")
    public List<TranscodeSessionDto> transcodeSessions(
            @AuthenticationPrincipal AppUser user,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {

        requireAdmin(key);
        return transcodes.activeSessions().stream()
                .map(session -> new TranscodeSessionDto(
                        session.getId(),
                        session.getMovieId(),
                        "/api/media/transcode/" + session.getId() + "/index.m3u8",
                        session.getStartSeconds(),
                        session.getHeight(),
                        session.getState().name()))
                .toList();
    }

    private void requireAdmin(String key) {
        if (adminKey == null || adminKey.isBlank() || !adminKey.equals(key)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Admin key required");
        }
    }
}
