package com.example.kido.media.engagement.mirror;

import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * The wire shapes for the mirror watchlist.
 *
 * <p>Kept to this feature rather than folded into {@code EngagementDtos}: a like is a
 * boolean and a count, a saved item is a title snapshot, and the two have nothing in
 * common but the mirror id. These are their own small records so neither shape has to
 * carry a field the other wants.
 */
public final class MirrorSavedDtos {

    private MirrorSavedDtos() {}

    /**
     * The snapshot the client sends when it saves a title.
     *
     * <p>Only the name is required — it is the one field a list cannot render without.
     * Everything else is best-effort: a tile saved from search carries a poster and little
     * else, one saved from the detail screen carries the lot. The bounds match the
     * entity's columns so an over-long value is a 400 here rather than a truncation or a
     * database error later. The mirror id is not in the body; it rides the path.
     */
    public record SaveRequest(
            @Size(max = MirrorSavedItem.NAME_MAX) String name,
            @Size(max = MirrorSavedItem.POSTER_MAX) String posterUrl,
            Integer year,
            @Size(max = 64) String ott,
            @Size(max = 64) String type) {
    }

    /** Whether this profile has the title on their list — the PUT/DELETE/GET answer. */
    public record SavedStatusDto(String mirrorId, boolean saved) {
    }

    /** One row of the list, enough to draw a poster tile and open the title. */
    public record SavedItemDto(
            String mirrorId,
            String name,
            String posterUrl,
            Integer year,
            String ott,
            String type,
            String savedAt) {
    }

    /** A page of the list, newest save first. */
    public record SavedPageDto(
            List<SavedItemDto> items,
            int page,
            int size,
            long totalItems,
            int totalPages) {
    }
}
