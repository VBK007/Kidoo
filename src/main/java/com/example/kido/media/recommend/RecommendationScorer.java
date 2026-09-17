package com.example.kido.media.recommend;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.recommend.TasteProfile.Facet;

/**
 * Orders candidates for one person, as opposed to for the library.
 *
 * <p>{@code PopularityRanker} answers "what is good here". This answers "what is good for
 * you tonight", which is a different question and needs its own scorer rather than an
 * extra weight on that one — the two have to be able to disagree, and a title everybody
 * else loves that this profile has walked away from is exactly where they should.
 *
 * <p>Pure and static, for the same reason: the ordering is the judgement, and a judgement
 * should be testable without a database.
 *
 * <p>There is no "availability" term, though it was planned. Every candidate reaching
 * this scorer is already present, visible and of a playable type — the query said so — so
 * a term for it would score every row identically and read like a factor while doing
 * nothing.
 */
public final class RecommendationScorer {

    /**
     * How much each part counts. Taste leads, because that is the only thing separating
     * this from the home screen's existing rails. Quality is second and does real work:
     * a taste match nobody rates well is how a recommender ends up defending its own
     * choices. Freshness breaks the tie towards something they have not already scrolled
     * past a hundred times.
     */
    public static final double TASTE_WEIGHT = 0.50;
    public static final double QUALITY_WEIGHT = 0.30;
    public static final double FRESHNESS_WEIGHT = 0.20;

    /** Ratings are 0–10, as the sidecar parser normalises them. */
    private static final double RATING_SCALE = 10.0;

    /** Scored with the library's mean when unrated, as the popularity ranker does. */
    private static final double NEUTRAL_RATING = 5.0;

    /** Newer than this counts as fully fresh; older decays to nothing over a year. */
    private static final Duration FULLY_FRESH = Duration.ofDays(30);
    private static final double FRESHNESS_FLOOR_DAYS = 365.0;

    private RecommendationScorer() {}

    /**
     * One recommendation, with the parts kept separate so a support question — or the
     * person themselves — can see why it placed where it did.
     *
     * @param reason what to print under the poster, naming the evidence where there is
     *               any: "Because you watched Kaithi" is checkable in a way that
     *               "Recommended for you" is not
     */
    public record Recommended(
            MediaItem item,
            double score,
            double tasteScore,
            double qualityScore,
            double freshnessScore,
            String reason) {}

    /**
     * Ranks candidates for a profile, best first.
     *
     * @param candidates already filtered to things worth suggesting — present, visible,
     *                   and not already finished by this profile
     * @param taste      may be empty, in which case this degrades to quality and
     *                   freshness, which is a sensible first screen rather than no screen
     * @param meanRating the library's mean, for unrated titles; null falls back to neutral
     */
    public static List<Recommended> rank(Collection<MediaItem> candidates,
                                         TasteProfile taste,
                                         Double meanRating,
                                         Instant now,
                                         int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        double prior = meanRating == null || meanRating <= 0 ? NEUTRAL_RATING : meanRating;

        List<Recommended> scored = new ArrayList<>(candidates.size());
        for (MediaItem item : candidates) {
            scored.add(score(item, taste, prior, now));
        }

        scored.sort(Comparator
                .comparingDouble(Recommended::score).reversed()
                // Ties broken by title so the rail does not reshuffle between requests.
                .thenComparing(recommended -> sortKey(recommended.item())));
        return scored.size() <= limit ? scored : List.copyOf(scored.subList(0, limit));
    }

    /** Exposed so one title's standing can be explained without ranking the pool. */
    public static Recommended score(MediaItem item, TasteProfile taste, double meanRating,
                                    Instant now) {
        Set<Facet> facets = TasteProfile.facetsOf(item);
        double tasteScore = tasteMatch(facets, taste);
        double rating = item.getRating() == null ? meanRating : item.getRating();
        double qualityScore = clamp(rating / RATING_SCALE);
        double freshnessScore = freshness(item, now);

        double total = TASTE_WEIGHT * tasteScore
                + QUALITY_WEIGHT * qualityScore
                + FRESHNESS_WEIGHT * freshnessScore;

        return new Recommended(item, round(clamp(total)), round(tasteScore),
                round(qualityScore), round(freshnessScore),
                reasonFor(item, facets, taste, tasteScore));
    }

    /**
     * How well a title matches, as the mean of the facets it actually carries.
     *
     * <p>The mean rather than the maximum, because the maximum makes one strong facet
     * enough: every film with a favourite actor in a bit part would outrank a film that
     * is right in every other way. Averaging over the facets an item carries means a
     * title has to be broadly right, and lets a disliked facet pull it back down.
     */
    private static double tasteMatch(Set<Facet> facets, TasteProfile taste) {
        if (taste == null || taste.isEmpty() || facets.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (Facet facet : facets) {
            total += taste.weightOf(facet);
        }
        // Clamped rather than normalised away: a negative mean is a real verdict and
        // should floor the term, not wrap around it.
        return clamp(total / facets.size());
    }

    /**
     * Recently added, decaying over a year.
     *
     * <p>Not "unwatched" — the candidate set is already only things they have not
     * finished, so a term for it would be another constant.
     */
    private static double freshness(MediaItem item, Instant now) {
        if (item.getAddedAt() == null) {
            return 0;
        }
        Duration age = Duration.between(item.getAddedAt(), now);
        if (age.isNegative() || age.compareTo(FULLY_FRESH) <= 0) {
            return 1.0;
        }
        double days = age.toDays();
        return clamp(1.0 - (days - FULLY_FRESH.toDays()) / FRESHNESS_FLOOR_DAYS);
    }

    /**
     * Names the evidence, preferring a title the person actually watched.
     *
     * <p>The strongest facet is found first, then the title that established it. Where
     * there is no history the reason falls back to something true rather than something
     * vague — an unexplained recommendation is one nobody can argue with, which is worse
     * than a weak one.
     */
    private static String reasonFor(MediaItem item, Set<Facet> facets, TasteProfile taste,
                                    double tasteScore) {
        Optional<Facet> strongest = facets.stream()
                .filter(facet -> taste != null && taste.weightOf(facet) > 0)
                .max(Comparator.comparingDouble(taste::weightOf));

        if (tasteScore > 0 && strongest.isPresent()) {
            Facet facet = strongest.get();
            Optional<String> because = taste.evidenceFor(facet);
            if (because.isPresent() && !because.get().equals(item.getTitle())) {
                return "Because you watched " + because.get();
            }
            return "Matches your " + label(facet);
        }
        if (item.getRating() != null) {
            return String.format(Locale.ROOT, "Rated %.1f", item.getRating());
        }
        return "New to you";
    }

    private static String label(Facet facet) {
        return switch (facet.kind()) {
            case GENRE -> facet.value() + " films";
            case PERSON -> "taste in cast";
            case LANGUAGE -> "usual language";
            case DECADE -> "taste in " + facet.value() + "s films";
        };
    }

    private static String sortKey(MediaItem item) {
        if (item.getSortTitle() != null) {
            return item.getSortTitle();
        }
        return item.getTitle() == null ? item.getId() : item.getTitle();
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /** Three decimals: enough to order by, short enough to read in a response. */
    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
