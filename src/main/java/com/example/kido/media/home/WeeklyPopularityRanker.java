package com.example.kido.media.home;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.example.kido.media.catalog.MediaItem;

/**
 * Ranks a pool of items by activity in a recent window — plays, likes and comments
 * earned inside it — rather than the lifetime totals {@link PopularityRanker} blends.
 *
 * <p>A weekly "trending" rail is a different question from "the best of the library":
 * a title from three years ago with a thousand lifetime plays would permanently bury
 * whatever the household is actually into this week, so nothing here reads {@link
 * MediaItem#playCount()} or {@link MediaItem#getLikeCount()} — every signal is a
 * windowed count the caller already fetched (see {@code WatchEventRepository
 * #viewCountsByItemSince}, {@code MediaItemLikeRepository#countsByItemSince},
 * {@code MediaItemCommentRepository#countsByItemSince}), keyed by item id since none of
 * it lives on the entity itself.
 *
 * <p>Same log-scaled normalisation as {@link PopularityRanker} and for the same reason:
 * one title a household binges pushes a linear scale to the point every other title
 * reads as zero.
 */
public final class WeeklyPopularityRanker {

    /**
     * Views lead because a title has to actually be watched before a like or a comment
     * on it means anything; comments outweigh likes despite being rarer, since writing
     * one costs more than tapping a heart.
     */
    public static final double VIEW_WEIGHT = 0.5;
    public static final double LIKE_WEIGHT = 0.2;
    public static final double COMMENT_WEIGHT = 0.3;

    private WeeklyPopularityRanker() {}

    /**
     * @param score 0–1, the weighted blend of this window's activity
     */
    public record Scored(MediaItem item, double score, String reason) {}

    /**
     * Ranks a candidate pool, best first.
     *
     * @param pool     candidates — every item that had at least one of the three
     *                 signals in the window, deduplicated by the caller
     * @param views    {@code mediaItemId -> watch-event count} in the window
     * @param likes    {@code mediaItemId -> like count} in the window
     * @param comments {@code mediaItemId -> comment count} in the window
     * @param limit    how many to return
     */
    public static List<Scored> rank(Collection<MediaItem> pool,
                                    Map<String, Long> views,
                                    Map<String, Long> likes,
                                    Map<String, Long> comments,
                                    int limit) {
        if (pool == null || pool.isEmpty() || limit <= 0) {
            return List.of();
        }

        long maxViews = views.values().stream().mapToLong(Long::longValue).max().orElse(0);
        long maxLikes = likes.values().stream().mapToLong(Long::longValue).max().orElse(0);
        long maxComments = comments.values().stream().mapToLong(Long::longValue).max().orElse(0);

        List<Scored> scored = new ArrayList<>(pool.size());
        for (MediaItem item : pool) {
            long viewCount = views.getOrDefault(item.getId(), 0L);
            long likeCount = likes.getOrDefault(item.getId(), 0L);
            long commentCount = comments.getOrDefault(item.getId(), 0L);

            double viewScore = logScaled(viewCount, maxViews);
            double likeScore = logScaled(likeCount, maxLikes);
            double commentScore = logScaled(commentCount, maxComments);
            double total = VIEW_WEIGHT * viewScore + LIKE_WEIGHT * likeScore + COMMENT_WEIGHT * commentScore;

            scored.add(new Scored(item, round(total),
                    reasonFor(viewCount, likeCount, commentCount)));
        }

        scored.sort(Comparator
                .comparingDouble(Scored::score).reversed()
                .thenComparing(s -> sortKey(s.item())));
        return scored.size() <= limit ? scored : List.copyOf(scored.subList(0, limit));
    }

    private static String reasonFor(long views, long likes, long comments) {
        if (views > 0) {
            return views == 1 ? "Watched once this week" : "Watched " + views + " times this week";
        }
        if (comments > 0) {
            return comments == 1 ? "1 comment this week" : comments + " comments this week";
        }
        if (likes > 0) {
            return likes == 1 ? "Liked this week" : "Liked " + likes + " times this week";
        }
        return "Trending this week";
    }

    private static double logScaled(long value, long max) {
        if (max <= 0 || value <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(1, Math.log1p(value) / Math.log1p(max)));
    }

    private static String sortKey(MediaItem item) {
        if (item.getSortTitle() != null) {
            return item.getSortTitle();
        }
        return item.getTitle() == null ? item.getId() : item.getTitle();
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
