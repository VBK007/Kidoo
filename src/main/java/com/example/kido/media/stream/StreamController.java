package com.example.kido.media.stream;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.example.kido.media.session.PlaybackSessionRegistry;
import com.example.kido.media.together.WatchPartyGrants;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;
import com.example.kido.security.GuestPrincipal;

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
    private final LibraryIngestService ingest;
    private final PlaybackSessionRegistry sessions;
    private final PlaybackStarter starter;
    private final WatchPartyGrants grants;

    public StreamController(CatalogService catalog,
                            MediaPaths paths,
                            FileStreamer streamer,
                            PlaybackDecisionService decisions,
                            LibraryIngestService ingest,
                            PlaybackSessionRegistry sessions,
                            PlaybackStarter starter,
                            WatchPartyGrants grants) {
        this.catalog = catalog;
        this.paths = paths;
        this.streamer = streamer;
        this.decisions = decisions;
        this.ingest = ingest;
        this.sessions = sessions;
        this.starter = starter;
        this.grants = grants;
    }

    /**
     * Works out how this client should play this title, and opens a session for it.
     *
     * <p>The sequence itself lives in {@link PlaybackStarter}, because watch party
     * guests need exactly the same one and cannot reach this endpoint: it is
     * profile-scoped, and a guest has no profile.
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

        return starter.start(profile.getId(), profile.getName(), id, startSeconds,
                capabilities, request.getRemoteAddr());
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
    public void stream(@AuthenticationPrincipal GuestPrincipal guest,
                       @PathVariable String id,
                       @RequestParam(name = "session", required = false) String sessionId,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {

        // Null for an account holder, who was already cleared by the security chain.
        grants.requirePlayable(guest, id);

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
