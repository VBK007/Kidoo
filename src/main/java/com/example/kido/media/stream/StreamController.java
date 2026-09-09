package com.example.kido.media.stream;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaFiles;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.dto.CatalogDtos.MediaInfoDto;
import com.example.kido.media.dto.PlaybackDtos.ClientCapabilitiesRequest;
import com.example.kido.media.dto.PlaybackDtos.PlaybackDecisionDto;
import com.example.kido.media.library.LibraryIngestService;
import com.example.kido.media.session.PlaybackSession;
import com.example.kido.media.session.PlaybackSessionRegistry;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Playback entry points: the decision the app makes before playing, and the
 * direct-play byte stream itself.
 *
 * <p>The app is expected to call {@code /playback-decision} first and then use whatever
 * URL comes back rather than assuming direct play. The decision is where probing,
 * capability matching, transcode setup and session registration all happen — and the
 * URL it returns carries the session id, which is what lets the owner see and end the
 * stream from the admin panel.
 */
@Slf4j
@RestController
@RequestMapping("/api/media/items/{id}")
public class StreamController {

    /** Direct-play responses cache briefly; the bytes never change for a given file. */
    private static final long VIDEO_CACHE_SECONDS = 3600;

    private final CatalogService catalog;
    private final MediaPaths paths;
    private final FileStreamer streamer;
    private final PlaybackDecisionService decisions;
    private final TranscodeSessionManager transcodes;
    private final LibraryIngestService ingest;
    private final PlaybackSessionRegistry sessions;

    public StreamController(CatalogService catalog,
                            MediaPaths paths,
                            FileStreamer streamer,
                            PlaybackDecisionService decisions,
                            TranscodeSessionManager transcodes,
                            LibraryIngestService ingest,
                            PlaybackSessionRegistry sessions) {
        this.catalog = catalog;
        this.paths = paths;
        this.streamer = streamer;
        this.decisions = decisions;
        this.transcodes = transcodes;
        this.ingest = ingest;
        this.sessions = sessions;
    }

    /**
     * Works out how this client should play this title, and opens a session for it.
     *
     * <p>Probes the file first if it has never been probed, since the decision is
     * meaningless without knowing what is inside the container. When a transcode is
     * needed the ffmpeg session is started here, so the returned playlist is already
     * serving segments by the time the client asks for it.
     *
     * @param startSeconds where playback will begin — a transcode is seeked to this
     *                     offset, so resuming mid-film does not re-encode from zero
     */
    @PostMapping("/playback-decision")
    public PlaybackDecisionDto decide(@ActiveProfile Profile profile,
                                      @PathVariable String id,
                                      @RequestParam(defaultValue = "0") double startSeconds,
                                      @Valid @RequestBody ClientCapabilitiesRequest capabilities,
                                      HttpServletRequest request) {

        MediaItem item = catalog.require(id);
        Path file = paths.requireWithinRoots(item.getFilePath());
        item = ingest.ensureProbed(item, file);

        PlaybackDecisionService.Decision decision = decisions.decide(item, capabilities);
        catalog.recordPlaybackDecision(item.getId(), decision.directPlay());

        if (decision.directPlay()) {
            PlaybackSession session = sessions.start(
                    profile, item, PlaybackDecisionDto.Mode.DIRECT, capabilities.deviceName(),
                    request.getRemoteAddr(), null, null, startSeconds);

            // The session parameter is what makes a direct play visible in the admin
            // panel and stoppable from it; a plain /stream still works without one.
            return new PlaybackDecisionDto(
                    item.getId(),
                    PlaybackDecisionDto.Mode.DIRECT,
                    "/api/media/items/" + item.getId() + "/stream?session=" + session.getId(),
                    session.getId(),
                    startSeconds,
                    MediaInfoDto.from(item.getMediaInfo()),
                    decision.reasons());
        }

        if (!capabilities.hlsAllowed()) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "This file needs transcoding but the client declared no HLS support: "
                            + String.join("; ", decision.reasons()));
        }

        TranscodeSession transcode = transcodes.start(
                item, file, Math.max(0, startSeconds), decision.targetHeight());

        PlaybackSession session = sessions.start(
                profile, item, PlaybackDecisionDto.Mode.TRANSCODE, capabilities.deviceName(),
                request.getRemoteAddr(), transcode.getId(), decision.targetHeight(), startSeconds);

        return new PlaybackDecisionDto(
                item.getId(),
                PlaybackDecisionDto.Mode.TRANSCODE,
                "/api/media/transcode/" + transcode.getId() + "/index.m3u8",
                session.getId(),
                startSeconds,
                MediaInfoDto.from(item.getMediaInfo()),
                decision.reasons());
    }

    /**
     * Serves the original file with {@code Range} support.
     *
     * <p>Safe to call without a session — it is just bytes — but passing the session
     * from the decision is what meters the stream and lets the owner end it. A session
     * the owner has ended is refused here, which is how "end this stream" reaches a
     * direct play: there is no process to kill, so the next range request is stopped
     * instead, and a player asking every few seconds halts almost immediately.
     */
    @GetMapping("/stream")
    public void stream(@PathVariable String id,
                       @RequestParam(name = "session", required = false) String sessionId,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {

        if (!sessions.isServable(sessionId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This stream was ended by the owner");
        }
        MediaItem item = catalog.require(id);
        Path file = paths.requireWithinRoots(item.getFilePath());

        long written = streamer.serve(file, MediaFiles.contentType(item.getFileName()),
                VIDEO_CACHE_SECONDS, request, response);
        sessions.recordBytes(sessionId, written);
    }

    /** Diagnostics for the app's debug screen: what the server thinks is in the file. */
    @GetMapping("/media-info")
    public MediaInfoDto mediaInfo(@PathVariable String id) {
        MediaItem item = catalog.require(id);
        Path file = paths.requireWithinRoots(item.getFilePath());
        return MediaInfoDto.from(ingest.ensureProbed(item, file).getMediaInfo());
    }

    /**
     * Reasons the decision engine would give, without starting anything.
     *
     * <p>Answered from the cached probe alone, so unlike {@code /playback-decision}
     * this works when the disk is offline — which is exactly when someone is trying to
     * work out why a title will not play. No session is opened and no counter moves.
     */
    @PostMapping("/playback-decision/explain")
    public List<String> explain(@PathVariable String id,
                                @Valid @RequestBody ClientCapabilitiesRequest capabilities) {
        MediaItem item = catalog.require(id);
        try {
            item = ingest.ensureProbed(item, paths.requireWithinRoots(item.getFilePath()));
        } catch (ApiException ex) {
            // Unreachable file: fall through and explain from what is already known.
            log.debug("Explaining {} without disk access: {}", id, ex.getMessage());
        }
        return decisions.decide(item, capabilities).reasons();
    }
}
