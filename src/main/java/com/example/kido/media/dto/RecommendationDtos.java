package com.example.kido.media.dto;

import java.util.List;

import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;

/**
 * Recommendations on the wire.
 *
 * <p>Every pick carries its reason and its parts. That is not instrumentation — a
 * recommendation nobody can argue with is one nobody can correct, and the parts are what
 * turn "the server thinks you'll like this" into a claim with a shape.
 */
public final class RecommendationDtos {

    private RecommendationDtos() {}

    /**
     * @param score     0–1 blend of the three below
     * @param reason    the poster subtitle, naming evidence where there is any:
     *                  {@code Because you watched Kaithi}
     * @param taste     how well it matches what this profile has actually watched
     * @param quality   its rating, or the library's mean where it has none
     * @param freshness how recently it appeared on the disk
     */
    public record RecommendationDto(
            ItemSummaryDto item,
            double score,
            String reason,
            double taste,
            double quality,
            double freshness) {}

    /**
     * One facet the server believes this profile likes.
     *
     * @param kind     {@code genre}, {@code person}, {@code language} or {@code decade}
     * @param weight   0–1 within its kind
     * @param evidence the title that did most to establish it, where there is one
     */
    public record TasteFacetDto(String kind, String value, double weight, String evidence) {}

    /**
     * @param taste       what the server thinks it knows, so a client can show it and a
     *                    person can see it is wrong
     * @param basedOn     how many titles the taste was derived from; 0 means the picks
     *                    are quality and freshness alone
     * @param coldStart   true when there is no history at all — a client may want to say
     *                    so rather than present guesses as knowledge
     */
    public record RecommendationsDto(
            List<RecommendationDto> items,
            List<TasteFacetDto> taste,
            int basedOn,
            boolean coldStart) {}
}
