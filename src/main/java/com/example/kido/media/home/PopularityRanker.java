package com.example.kido.media.home;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.example.kido.media.catalog.MediaItem;

/**
 * Turns three unrelated signals — the scraper's rating, how often a title has been
 * played and how many people in the house liked it — into one order for the home
 * screen's headline rail.
 *
 * <p>Pure and static so the ordering can be tested without a database. Each signal is
 * normalised to 0–1 before it is weighted, because they are not comparable as they
 * stand: a rating is bounded at 10, while plays and likes are unbounded counts whose
 * ceilings differ by an order of magnitude between a fresh install and a library that
 * has been in use for a year. Weighting the raw numbers would let play count drown out
 * everything else within a week.
 *
 * <p>Counts are normalised on a log scale rather than linearly. On a home server one
 * title is always watched far more than the rest — the film the kids replay every
 * weekend — and dividing by that maximum linearly would push every other title to
 * near-zero and leave the rail sorted by rating alone. The log keeps the runner-up
 * meaningfully close.
 *
 * <p>An unrated title is scored with the library's mean rating rather than zero. Most
 * home footage has no sidecar rating, and scoring a missing value as "terrible" would
 * mean the rail could never show anything the scraper had not matched.
 */
public final class PopularityRanker {

    /**
     * How much each signal counts. Rating leads because it is the only signal that
     * exists before anyone has watched anything — a library with no history still has
     * to produce a sensible home screen. Likes are weighted below plays despite being
     * the more deliberate signal, simply because there are far fewer of them.
     */
    public static final double RATING_WEIGHT = 0.40;
    public static final double VIEW_WEIGHT = 0.35;
    public static final double LIKE_WEIGHT = 0.25;

    /** Ratings are on a 0–10 scale, as normalised by the sidecar parser. */
    private static final double RATING_SCALE = 10.0;

    /** Used when the library has no rated titles at all, so there is no mean to borrow. */
    private static final double NEUTRAL_RATING = 5.0;

    private PopularityRanker() {}

    /**
     * One item's standing, with the parts kept separate so a client (or a support
     * question) can see why it placed where it did.
     *
     * @param score  0–1, the weighted blend
     * @param reason the single strongest contributor, phrased for a poster subtitle
     */
    public record Scored(
            MediaItem item,
            double score,
            double ratingScore,
            double viewScore,
            double likeScore,
            String reason) {}

    /**
     * Ranks a candidate pool, best first.
     *
     * @param pool       candidates, already deduplicated by the caller
     * @param meanRating library mean used for unrated items; null falls back to neutral
     * @param limit      how many to return
     */
    public static List<Scored> rank(Collection<MediaItem> pool, Double meanRating, int limit) {
        if (pool == null || pool.isEmpty() || limit <= 0) {
            return List.of();
        }

        // Maxima come from the pool, which by construction contains the library's
        // most-played and most-liked titles, so these are the global maxima.
        long maxViews = pool.stream().mapToLong(MediaItem::playCount).max().orElse(0);
        long maxLikes = pool.stream().mapToLong(MediaItem::getLikeCount).max().orElse(0);
        double prior = meanRating == null || meanRating <= 0 ? NEUTRAL_RATING : meanRating;

        List<Scored> scored = new ArrayList<>(pool.size());
        for (MediaItem item : pool) {
            scored.add(score(item, prior, maxViews, maxLikes));
        }

        scored.sort(Comparator
                .comparingDouble(Scored::score).reversed()
                // Ties broken by title so the rail does not reshuffle between requests.
                .thenComparing(s -> sortKey(s.item())));
        return scored.size() <= limit ? scored : List.copyOf(scored.subList(0, limit));
    }

    /** Exposed so a single item's standing can be explained without ranking the pool. */
    public static Scored score(MediaItem item, double meanRating, long maxViews, long maxLikes) {
        double rating = item.getRating() == null ? meanRating : item.getRating();
        double ratingScore = clamp(rating / RATING_SCALE);
        double viewScore = logScaled(item.playCount(), maxViews);
        double likeScore = logScaled(item.getLikeCount(), maxLikes);

        double total = RATING_WEIGHT * ratingScore
                + VIEW_WEIGHT * viewScore
                + LIKE_WEIGHT * likeScore;

        return new Scored(item, round(total), round(ratingScore), round(viewScore),
                round(likeScore), reasonFor(item, ratingScore, viewScore, likeScore));
    }

    /**
     * {@code log(1+n) / log(1+max)} — 0 for an untouched title, 1 for the leader, and
     * a curve in between that does not collapse the middle of the library.
     */
    private static double logScaled(long value, long max) {
        if (max <= 0 || value <= 0) {
            return 0;
        }
        return clamp(Math.log1p(value) / Math.log1p(max));
    }

    /** Names the signal that actually carried the item, for the client's subtitle. */
    private static String reasonFor(MediaItem item,
                                    double ratingScore,
                                    double viewScore,
                                    double likeScore) {
        double weightedRating = RATING_WEIGHT * ratingScore;
        double weightedViews = VIEW_WEIGHT * viewScore;
        double weightedLikes = LIKE_WEIGHT * likeScore;

        if (weightedLikes >= weightedViews && weightedLikes >= weightedRating
                && item.getLikeCount() > 0) {
            return likeLabel(item);
        }
        if (weightedViews >= weightedRating && item.playCount() > 0) {
            return viewLabel(item);
        }
        if (item.getRating() != null) {
            return ratingLabel(item);
        }
        return "In your library";
    }

    // The single source of the phrasing, so a title reads the same whether it arrived
    // on the blended rail or on the one rail that ranks by that signal alone.

    public static String ratingLabel(MediaItem item) {
        return item.getRating() == null
                ? "Not rated"
                : String.format(Locale.ROOT, "Rated %.1f", item.getRating());
    }

    public static String viewLabel(MediaItem item) {
        long plays = item.playCount();
        if (plays == 0) {
            return "Not played yet";
        }
        return plays == 1 ? "Played once" : "Played " + plays + " times";
    }

    public static String likeLabel(MediaItem item) {
        long count = item.getLikeCount();
        if (count == 0) {
            return "No likes yet";
        }
        return count == 1 ? "Liked in your house" : "Liked by " + count + " in your house";
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
