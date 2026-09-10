package com.example.kido.media.stream;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.dto.PlaybackDtos.TranscodeSessionDto;
import com.example.kido.media.session.PlaybackSessionRegistry;
import com.example.kido.media.together.WatchPartyGrants;
import com.example.kido.security.GuestPrincipal;
import com.example.kido.user.AppUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves the playlist and segments of a running transcode.
 *
 * <p>The playlist ffmpeg writes uses bare relative segment names, and because the
 * playlist is served from {@code /api/media/transcode/{id}/index.m3u8} the client
 * resolves those against {@code /api/media/transcode/{id}/} — which is exactly the
 * segment endpoint below. So the playlist is passed through untouched.
 *
 * <p>Every request here bumps the session's idle timer, which is what keeps a session
 * alive while someone is watching and lets the reaper collect it once they stop.
 */
@Slf4j
@RestController
@RequestMapping("/api/media/transcode/{sessionId}")
public class HlsController {

    /** Playlists must not be cached: the file grows as ffmpeg produces segments. */
    private static final long PLAYLIST_CACHE_SECONDS = 0;

    /** Segments are immutable once written, and only exist for this session. */
    private static final long SEGMENT_CACHE_SECONDS = 3600;

    private final TranscodeSessionManager sessions;
    private final FileStreamer streamer;
    private final PlaybackSessionRegistry playbackSessions;
    private final WatchPartyGrants grants;

    public HlsController(TranscodeSessionManager sessions,
                         FileStreamer streamer,
                         PlaybackSessionRegistry playbackSessions,
                         WatchPartyGrants grants) {
        this.sessions = sessions;
        this.streamer = streamer;
        this.playbackSessions = playbackSessions;
        this.grants = grants;
    }

    /**
     * Checks a guest against the title behind a transcode, not against its id.
     *
     * <p>A transcode session id is opaque, which is not the same as secret: it travels
     * in URLs and logs. What authorises a guest is the film the session is producing,
     * so that is what gets compared.
     */
    private void requireGrant(GuestPrincipal guest, String sessionId) {
        if (guest != null) {
            grants.requirePlayable(guest, sessions.require(sessionId).getMovieId());
        }
    }

    @GetMapping("/index.m3u8")
    public void playlist(@AuthenticationPrincipal AppUser user,
                         @AuthenticationPrincipal GuestPrincipal guest,
                         @PathVariable String sessionId,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {

        requireGrant(guest, sessionId);
        Path playlist = sessions.playlistFile(sessionId);
        streamer.serve(playlist, "application/vnd.apple.mpegurl",
                PLAYLIST_CACHE_SECONDS, request, response);
    }

    /**
     * @param segment ffmpeg-generated name; validated against a strict pattern by the
     *                session manager before it is resolved against the session directory
     */
    @GetMapping("/{segment}")
    public void segment(@AuthenticationPrincipal AppUser user,
                        @AuthenticationPrincipal GuestPrincipal guest,
                        @PathVariable String sessionId,
                        @PathVariable String segment,
                        HttpServletRequest request,
                        HttpServletResponse response) throws IOException {

        requireGrant(guest, sessionId);
        Path file = sessions.segmentFile(sessionId, segment);
        long written = streamer.serve(file, "video/mp2t", SEGMENT_CACHE_SECONDS, request, response);
        // Metered against the playback session so the admin panel can report the real
        // outbound rate of a transcode, not just of direct plays.
        playbackSessions.recordBytesForTranscode(sessionId, written);
    }

    /**
     * Releases the session and its scratch space.
     *
     * <p>Worth calling when the user stops playback: it frees a transcode slot straight
     * away rather than after the idle timeout. The reaper covers clients that vanish.
     */
    @DeleteMapping
    public ResponseEntity<Void> stop(@AuthenticationPrincipal AppUser user,
                                     @AuthenticationPrincipal GuestPrincipal guest,
                                     @PathVariable String sessionId) {
        requireGrant(guest, sessionId);
        sessions.stop(sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    public TranscodeSessionDto status(@AuthenticationPrincipal AppUser user,
                                      @AuthenticationPrincipal GuestPrincipal guest,
                                      @PathVariable String sessionId) {
        requireGrant(guest, sessionId);
        TranscodeSession session = sessions.require(sessionId);
        return toDto(session);
    }

    static TranscodeSessionDto toDto(TranscodeSession session) {
        return new TranscodeSessionDto(
                session.getId(),
                session.getMovieId(),
                "/api/media/transcode/" + session.getId() + "/index.m3u8",
                session.getStartSeconds(),
                session.getHeight(),
                session.getState().name());
    }
}
