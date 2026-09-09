package com.example.kido.media.downloads;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.MediaFiles;
import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.dto.DownloadDtos.DownloadJobDto;
import com.example.kido.media.dto.DownloadDtos.DownloadListDto;
import com.example.kido.media.dto.DownloadDtos.DownloadRequest;
import com.example.kido.media.dto.DownloadDtos.MediaSettingsDto;
import com.example.kido.media.dto.DownloadDtos.PlaybackCostDto;
import com.example.kido.media.dto.DownloadDtos.ReachabilityDto;
import com.example.kido.media.dto.DownloadDtos.UpdateMediaSettingsRequest;
import com.example.kido.media.stream.FileStreamer;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Offline copies, the away-from-home decision, and per-profile media settings.
 *
 * <p>Scoped to the active profile throughout: a download belongs to whoever asked for
 * it, and one profile must not be able to see or cancel another's.
 */
@RestController
@RequestMapping("/api/media")
public class DownloadController {

    private final DownloadService downloads;
    private final MediaSettingsService settings;
    private final ReachabilityService reachability;
    private final CatalogService catalog;
    private final FileStreamer streamer;

    public DownloadController(DownloadService downloads,
                              MediaSettingsService settings,
                              ReachabilityService reachability,
                              CatalogService catalog,
                              FileStreamer streamer) {
        this.downloads = downloads;
        this.settings = settings;
        this.reachability = reachability;
        this.catalog = catalog;
        this.streamer = streamer;
    }

    /**
     * Asks for an offline copy.
     *
     * <p>Returns 200 with a READY job when the original already suits the request,
     * since there is nothing to wait for, and 202 when a conversion has been queued.
     */
    @PostMapping("/items/{id}/download")
    public ResponseEntity<DownloadJobDto> request(@ActiveProfile Profile profile,
                                                  @PathVariable String id,
                                                  @Valid @RequestBody DownloadRequest request) {
        MediaItem item = catalog.require(id);
        DownloadJob job = downloads.request(
                profile, item, request.height(), request.capabilities());

        return job.isFetchable()
                ? ResponseEntity.ok(DownloadJobDto.from(job))
                : ResponseEntity.accepted().body(DownloadJobDto.from(job));
    }

    /**
     * The Saved list, paged newest first.
     *
     * <p>Header totals are counted server-side across every page, so they stay correct
     * once the history is longer than one page.
     */
    @GetMapping("/downloads")
    public DownloadListDto list(@ActiveProfile Profile profile,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {

        Page<DownloadJob> jobs = downloads.listFor(profile, page, size);
        DownloadService.SavedTotals totals = downloads.totalsFor(profile);

        return new DownloadListDto(
                jobs.getContent().stream().map(DownloadJobDto::from).toList(),
                jobs.getNumber(),
                jobs.getSize(),
                jobs.getTotalElements(),
                jobs.getTotalPages(),
                totals.readyCount(),
                totals.inProgressCount(),
                totals.readyBytes());
    }

    /** Polled while a conversion runs, for the percentage on the card. */
    @GetMapping("/downloads/{jobId}")
    public DownloadJobDto status(@ActiveProfile Profile profile, @PathVariable String jobId) {
        return DownloadJobDto.from(downloads.require(profile, jobId));
    }

    /**
     * Serves the prepared file.
     *
     * <p>Range-capable through the same streamer playback uses, which is what lets a
     * phone resume an interrupted download instead of starting the file again. Sent as
     * an attachment so the client saves it rather than trying to play it.
     */
    @GetMapping("/downloads/{jobId}/file")
    public void file(@ActiveProfile Profile profile,
                     @PathVariable String jobId,
                     HttpServletRequest request,
                     HttpServletResponse response) throws IOException {

        DownloadJob job = downloads.require(profile, jobId);
        Path file = downloads.fileFor(profile, jobId);

        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + suggestedName(job, file) + "\"");
        streamer.serve(file, MediaFiles.contentType(file.getFileName().toString()),
                0, request, response);
    }

    /** Cancels a queued or running conversion, or discards a prepared copy. */
    @DeleteMapping("/downloads/{jobId}")
    public DownloadJobDto cancel(@ActiveProfile Profile profile, @PathVariable String jobId) {
        return DownloadJobDto.from(downloads.cancel(profile, jobId));
    }

    /** Whether this client is on the home network. */
    @GetMapping("/reachability")
    public ReachabilityDto reachability(HttpServletRequest request) {
        return reachability.assess(request);
    }

    /** What playing a title would cost from wherever the client is now. */
    @GetMapping("/items/{id}/playback-cost")
    public PlaybackCostDto cost(@ActiveProfile Profile profile,
                                @PathVariable String id,
                                @RequestParam(required = false) Integer height,
                                HttpServletRequest request) {
        MediaItem item = catalog.require(id);
        ReachabilityDto where = reachability.assess(request);
        Integer preferred = height != null
                ? height
                : settings.forProfile(profile).getAwayMaxHeight();
        return reachability.costOf(item, where.atHome(), preferred);
    }

    @GetMapping("/settings")
    public MediaSettingsDto settings(@ActiveProfile Profile profile) {
        return MediaSettingsDto.from(settings.forProfile(profile));
    }

    /** Backs the "Play saved copies only" toggle and the sheet's "Remember this choice". */
    @PutMapping("/settings")
    public MediaSettingsDto updateSettings(
            @ActiveProfile Profile profile,
            @Valid @RequestBody UpdateMediaSettingsRequest request) {
        return MediaSettingsDto.from(settings.update(profile, request));
    }

    /**
     * A filename the phone can store sensibly.
     *
     * <p>Built from the title rather than the source filename, since a release name is
     * exactly what nobody wants in their downloads folder. Sanitised because the string
     * ends up in a header and then in a filesystem.
     */
    private static String suggestedName(DownloadJob job, Path file) {
        String title = job.getItemTitle() == null || job.getItemTitle().isBlank()
                ? "download"
                : job.getItemTitle();
        String safe = title.replaceAll("[^A-Za-z0-9 ._()-]", "").trim();
        if (safe.isEmpty()) {
            safe = "download";
        }
        String extension = MediaFiles.extension(file.getFileName().toString());
        return extension.isEmpty() ? safe : safe + "." + extension;
    }
}
