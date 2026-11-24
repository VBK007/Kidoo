package com.example.kido.media.engagement.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.engagement.mirror.MirrorProgressDtos.ProgressItemDto;
import com.example.kido.media.engagement.mirror.MirrorProgressDtos.ProgressPageDto;
import com.example.kido.media.engagement.mirror.MirrorProgressDtos.ProgressRequest;
import com.example.kido.profile.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * Continue Watching for mirror titles.
 *
 * <p>Where My List is a set — a title is on it or not — this is a dial, the one number of
 * how far a profile got, and so it parts from the My List pattern in the one place that
 * matters: {@link #record} is an upsert, not an idempotent insert. A title already in
 * progress keeps its row but takes the new position, the new duration and a fresh
 * {@code updatedAt}, because the whole point is that the mark moves. Saving a title twice
 * should change nothing; watching more of one should change exactly this.
 *
 * <p>Everything else is the mirror-engagement house style: keyed on profile so a household
 * resumes each to its own place, {@link MirrorIds#require} sanity-bounds the id, the title
 * snapshot rides along so the rail draws without a round trip, and the finished flag retires
 * a row from the rail while leaving it behind for a clean re-watch.
 */
@Slf4j
@Service
public class MirrorProgressService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MirrorProgressRepository progress;

    public MirrorProgressService(MirrorProgressRepository progress) {
        this.progress = progress;
    }

    /**
     * Write where the player got to — insert the row on first watch, move the mark on every
     * one after.
     *
     * <p>A negative position is clamped to zero rather than refused: the player reporting a
     * nonsense offset on a ragged seek should not cost the viewer their place, and zero is
     * the honest floor. The snapshot is refreshed each call from the latest request, so a
     * title that gained a poster between the first heartbeat and the last ends up with it.
     */
    @Transactional
    public ProgressItemDto record(Profile profile, String mirrorId, ProgressRequest request) {
        String id = MirrorIds.require(mirrorId);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Progress needs a body");
        }

        Optional<MirrorProgress> existing = progress.findByProfileIdAndMirrorId(profile.getId(), id);
        MirrorProgress row = existing.orElseGet(() -> MirrorProgress.builder()
                .profileId(profile.getId())
                .mirrorId(id)
                .build());

        row.setName(cleanName(request.name()));
        row.setPosterUrl(trimToNull(request.posterUrl()));
        row.setYear(request.year());
        row.setOtt(trimToNull(request.ott()));
        row.setType(trimToNull(request.type()));
        row.setEpisodeId(trimToNull(request.episodeId()));
        row.setPositionSeconds(Math.max(0d, request.positionSeconds()));
        row.setDurationSeconds(normalizeDuration(request.durationSeconds()));
        row.setFinished(request.finished());
        row.setUpdatedAt(Instant.now());

        try {
            progress.saveAndFlush(row);
        } catch (DataIntegrityViolationException ex) {
            // A first-watch insert raced another past the find; the unique constraint held.
            // Re-read and apply onto the row that won, so this call's position still lands.
            log.debug("Progress insert raced for profile {} on {}; merging", profile.getId(), id);
            MirrorProgress winner = progress.findByProfileIdAndMirrorId(profile.getId(), id)
                    .orElseThrow(() -> ex);
            winner.setName(row.getName());
            winner.setPosterUrl(row.getPosterUrl());
            winner.setYear(row.getYear());
            winner.setOtt(row.getOtt());
            winner.setType(row.getType());
            winner.setEpisodeId(row.getEpisodeId());
            winner.setPositionSeconds(row.getPositionSeconds());
            winner.setDurationSeconds(row.getDurationSeconds());
            winner.setFinished(row.isFinished());
            winner.setUpdatedAt(row.getUpdatedAt());
            progress.saveAndFlush(winner);
            row = winner;
        }
        return toDto(row);
    }

    /**
     * Where this profile left a single title, so the player can seek on open — or empty if
     * it has never been watched (or was watched to the end and so carries no resume point
     * the viewer would want).
     *
     * <p>A finished row is treated as no resume point: reopening a title watched to the end
     * should start it over, not drop the viewer onto the credits. The row still exists, so
     * the next play records fresh progress onto it.
     */
    @Transactional(readOnly = true)
    public Optional<ProgressItemDto> find(Profile profile, String mirrorId) {
        String id = MirrorIds.require(mirrorId);
        return progress.findByProfileIdAndMirrorId(profile.getId(), id)
                .filter(row -> !row.isFinished())
                .map(this::toDto);
    }

    /** Drop a title from Continue Watching outright — a swipe-away on the rail. Idempotent. */
    @Transactional
    public void remove(Profile profile, String mirrorId) {
        String id = MirrorIds.require(mirrorId);
        progress.deleteByProfileIdAndMirrorId(profile.getId(), id);
    }

    /** This profile's rail — unfinished, most recently watched first. Default 30, capped 100. */
    @Transactional(readOnly = true)
    public ProgressPageDto list(Profile profile, int page, int size) {
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<MirrorProgress> found = progress.findByProfileIdAndFinishedFalseOrderByUpdatedAtDescIdDesc(
                profile.getId(), PageRequest.of(Math.max(0, page), pageSize));

        List<ProgressItemDto> items = found.getContent().stream()
                .map(this::toDto)
                .toList();

        return new ProgressPageDto(items, found.getNumber(), found.getSize(),
                found.getTotalElements(), found.getTotalPages());
    }

    private ProgressItemDto toDto(MirrorProgress row) {
        return new ProgressItemDto(
                row.getMirrorId(),
                row.getName(),
                row.getPosterUrl(),
                row.getYear(),
                row.getOtt(),
                row.getType(),
                row.getEpisodeId(),
                row.getPositionSeconds(),
                row.getDurationSeconds(),
                row.isFinished(),
                row.getUpdatedAt().toString());
    }

    /**
     * A duration is kept only if it is a real positive length. Zero, negative or absent all
     * collapse to null, which the rail reads as "unknown" and draws no progress bar for —
     * better than a bar pinned at 100% or NaN because the player reported a length of zero.
     */
    private Double normalizeDuration(Double duration) {
        if (duration == null || duration <= 0d) {
            return null;
        }
        return duration;
    }

    /**
     * Trimmed, and refused if blank — the rail tile cannot be drawn without a name, the
     * same floor My List enforces past the length-only bean validation.
     */
    private String cleanName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A title needs a name to resume");
        }
        if (trimmed.length() > MirrorProgress.NAME_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That title name is too long");
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
