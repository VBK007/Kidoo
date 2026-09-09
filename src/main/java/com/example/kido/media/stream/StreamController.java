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
import com.example.kido.media.MediaPaths;
import com.example.kido.media.VideoFiles;
import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.Movie;
import com.example.kido.media.dto.CatalogDtos.MediaInfoDto;
import com.example.kido.media.dto.PlaybackDtos.ClientCapabilitiesRequest;
import com.example.kido.media.dto.PlaybackDtos.PlaybackDecisionDto;
import com.example.kido.media.library.LibraryIngestService;
import com.example.kido.user.AppUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Playback entry points: the decision call the app makes before playing, and the
 * direct-play byte stream itself.
 *
 * <p>The app is expected to call {@code /playback-decision} first and then use whatever
 * URL comes back, rather than assuming direct play — the decision is where probing,
 * capability matching and transcode-session setup happen.
 */
@Slf4j
@RestController
@RequestMapping("/api/media/movies/{id}")
public class StreamController {

    /** Direct-play responses are cached briefly; the bytes never change for a given file. */
    private static final long VIDEO_CACHE_SECONDS = 3600;

    private final CatalogService catalog;
    private final MediaPaths paths;
    private final FileStreamer streamer;
    private final PlaybackDecisionService decisions;
    private final TranscodeSessionManager transcodes;
    private final LibraryIngestService ingest;

    public StreamController(CatalogService catalog,
                            MediaPaths paths,
                            FileStreamer streamer,
                            PlaybackDecisionService decisions,
                            TranscodeSessionManager transcodes,
                            LibraryIngestService ingest) {
        this.catalog = catalog;
        this.paths = paths;
        this.streamer = streamer;
        this.decisions = decisions;
        this.transcodes = transcodes;
        this.ingest = ingest;
    }

    /**
     * Works out how this client should play this title.
     *
     * <p>Probes the file first if it has never been probed, since the decision is
     * meaningless without knowing what is inside the container. When a transcode is
     * needed, the ffmpeg session is started here and the returned playlist URL is
     * already serving segments by the time the client requests it.
     *
     * @param startSeconds where playback will begin — a transcode is seeked to this
     *                     offset, so resuming mid-film does not re-encode from zero
     */
    @PostMapping("/playback-decision")
    public PlaybackDecisionDto decide(@AuthenticationPrincipal AppUser user,
                                      @PathVariable String id,
                                      @RequestParam(defaultValue = "0") double startSeconds,
                                      @Valid @RequestBody ClientCapabilitiesRequest capabilities) {

        Movie movie = catalog.require(id);
        Path file = paths.requireWithinRoots(movie.getFilePath());
        movie = ingest.ensureProbed(movie, file);

        PlaybackDecisionService.Decision decision = decisions.decide(movie, capabilities);

        if (decision.directPlay()) {
            return new PlaybackDecisionDto(
                    movie.getId(),
                    PlaybackDecisionDto.Mode.DIRECT,
                    "/api/media/movies/" + movie.getId() + "/stream",
                    null,
                    startSeconds,
                    MediaInfoDto.from(movie.getMediaInfo()),
                    decision.reasons());
        }

        if (!capabilities.hlsAllowed()) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "This file needs transcoding but the client declared no HLS support: "
                            + String.join("; ", decision.reasons()));
        }

        TranscodeSession session = transcodes.start(
                movie, file, Math.max(0, startSeconds), decision.targetHeight());

        return new PlaybackDecisionDto(
                movie.getId(),
                PlaybackDecisionDto.Mode.TRANSCODE,
                "/api/media/transcode/" + session.getId() + "/index.m3u8",
                session.getId(),
                startSeconds,
                MediaInfoDto.from(movie.getMediaInfo()),
                decision.reasons());
    }

    /**
     * Serves the original file with {@code Range} support.
     *
     * <p>Safe to call without a decision — it is just bytes — but a client that has not
     * checked compatibility may find it cannot decode them.
     */
    @GetMapping("/stream")
    public void stream(@AuthenticationPrincipal AppUser user,
                       @PathVariable String id,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {

        Movie movie = catalog.require(id);
        Path file = paths.requireWithinRoots(movie.getFilePath());
        streamer.serve(file, VideoFiles.contentType(movie.getFileName()),
                VIDEO_CACHE_SECONDS, request, response);
    }

    /** Diagnostics for the app's debug screen: what the server thinks is inside the file. */
    @GetMapping("/media-info")
    public MediaInfoDto mediaInfo(@AuthenticationPrincipal AppUser user, @PathVariable String id) {
        Movie movie = catalog.require(id);
        Path file = paths.requireWithinRoots(movie.getFilePath());
        return MediaInfoDto.from(ingest.ensureProbed(movie, file).getMediaInfo());
    }

    /**
     * Reasons the decision engine would give, without starting a transcode.
     *
     * <p>Answered from the cached probe alone, so unlike {@code /playback-decision} this
     * works when the disk is offline — which is exactly when someone is trying to work
     * out why a title will not play. The file is probed only if it happens to be
     * reachable and has never been probed.
     */
    @PostMapping("/playback-decision/explain")
    public List<String> explain(@AuthenticationPrincipal AppUser user,
                                @PathVariable String id,
                                @Valid @RequestBody ClientCapabilitiesRequest capabilities) {
        Movie movie = catalog.require(id);
        try {
            movie = ingest.ensureProbed(movie, paths.requireWithinRoots(movie.getFilePath()));
        } catch (ApiException ex) {
            // Unreachable file: fall through and explain from what is already known.
            log.debug("Explaining {} without disk access: {}", id, ex.getMessage());
        }
        return decisions.decide(movie, capabilities).reasons();
    }
}
