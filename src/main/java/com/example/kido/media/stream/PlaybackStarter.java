package com.example.kido.media.stream;

import java.nio.file.Path;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.dto.CatalogDtos.MediaInfoDto;
import com.example.kido.media.dto.PlaybackDtos.ClientCapabilitiesRequest;
import com.example.kido.media.dto.PlaybackDtos.PlaybackDecisionDto;
import com.example.kido.media.library.LibraryIngestService;
import com.example.kido.media.session.PlaybackSession;
import com.example.kido.media.session.PlaybackSessionRegistry;

/**
 * Everything that has to happen before a client can play something.
 *
 * <p>Probing, the direct-play-or-transcode decision, starting ffmpeg when it is needed,
 * and opening the session that makes the stream visible and stoppable from the admin
 * panel — in that order, because each step depends on the one before it.
 *
 * <p>Lifted out of {@code StreamController} when watch party guests arrived. A guest
 * has no profile, so they cannot use the profile-scoped endpoint, but everything they
 * need to start playing is identical to what an account needs. Two callers, one
 * sequence: the only difference is the name the session is filed under.
 */
@Service
public class PlaybackStarter {

    private final CatalogService catalog;
    private final MediaPaths paths;
    private final PlaybackDecisionService decisions;
    private final TranscodeSessionManager transcodes;
    private final LibraryIngestService ingest;
    private final PlaybackSessionRegistry sessions;

    public PlaybackStarter(CatalogService catalog,
                           MediaPaths paths,
                           PlaybackDecisionService decisions,
                           TranscodeSessionManager transcodes,
                           LibraryIngestService ingest,
                           PlaybackSessionRegistry sessions) {
        this.catalog = catalog;
        this.paths = paths;
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
     * @param viewerId     what the session is filed under: a profile id for an account,
     *                     a party member id for a guest
     * @param viewerName   what the admin panel shows
     * @param startSeconds where playback will begin — a transcode is seeked to this
     *                     offset, so resuming mid-film does not re-encode from zero
     */
    public PlaybackDecisionDto start(String viewerId,
                                     String viewerName,
                                     String itemId,
                                     double startSeconds,
                                     ClientCapabilitiesRequest capabilities,
                                     String clientIp) {

        MediaItem item = catalog.require(itemId);
        Path file = paths.requireWithinRoots(item.getFilePath());
        item = ingest.ensureProbed(item, file);

        PlaybackDecisionService.Decision decision = decisions.decide(item, capabilities);
        catalog.recordPlaybackDecision(item.getId(), decision.directPlay());

        if (decision.directPlay()) {
            PlaybackSession session = sessions.start(
                    viewerId, viewerName, item, PlaybackDecisionDto.Mode.DIRECT,
                    capabilities.deviceName(), clientIp, null, null, startSeconds);

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
                viewerId, viewerName, item, PlaybackDecisionDto.Mode.TRANSCODE,
                capabilities.deviceName(), clientIp, transcode.getId(),
                decision.targetHeight(), startSeconds);

        return new PlaybackDecisionDto(
                item.getId(),
                PlaybackDecisionDto.Mode.TRANSCODE,
                "/api/media/transcode/" + transcode.getId() + "/index.m3u8",
                session.getId(),
                startSeconds,
                MediaInfoDto.from(item.getMediaInfo()),
                decision.reasons());
    }
}
