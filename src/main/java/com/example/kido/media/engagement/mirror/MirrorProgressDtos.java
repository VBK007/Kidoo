package com.example.kido.media.engagement.mirror;

import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * The wire shapes for Continue Watching on a mirror title.
 *
 * <p>Its own small records, for the same reason {@link MirrorSavedDtos} is: a saved item is
 * a title snapshot, a progress row is that same snapshot plus where the player got to, and
 * folding either into {@code EngagementDtos} would make every shape carry a field the
 * others ignore. The request is what the player writes on its heartbeat; the item is one
 * tile of the Continue Watching rail.
 */
public final class MirrorProgressDtos {

    private MirrorProgressDtos() {}

    /**
     * What the player sends to mark how far it has got — on a heartbeat, and once more
     * when it closes.
     *
     * <p>The title snapshot is the same best-effort set My List carries, for the same
     * reason: so the rail draws its tile without an ad-gated round trip per poster. Only
     * the name is required. {@code positionSeconds} is where the player is now; a null
     * {@code durationSeconds} just means the player did not know the length yet, and the
     * rail draws no progress bar for that row. {@code finished} true retires the row from
     * the rail. The mirror id rides the path, not the body.
     */
    public record ProgressRequest(
            @Size(max = MirrorProgress.NAME_MAX) String name,
            @Size(max = MirrorProgress.POSTER_MAX) String posterUrl,
            Integer year,
            @Size(max = 64) String ott,
            @Size(max = 64) String type,
            @Size(max = MirrorIds.MAX_LENGTH) String episodeId,
            double positionSeconds,
            Double durationSeconds,
            boolean finished) {
    }

    /**
     * One tile of the Continue Watching rail: the snapshot, plus where to resume and how
     * far through the title that is.
     */
    public record ProgressItemDto(
            String mirrorId,
            String name,
            String posterUrl,
            Integer year,
            String ott,
            String type,
            String episodeId,
            double positionSeconds,
            Double durationSeconds,
            boolean finished,
            String updatedAt) {
    }

    /** A page of the rail, most recently watched first. */
    public record ProgressPageDto(
            List<ProgressItemDto> items,
            int page,
            int size,
            long totalItems,
            int totalPages) {
    }
}
