package com.example.kido.media.home;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.engagement.MediaItemCommentRepository;
import com.example.kido.media.engagement.MediaItemLikeRepository;
import com.example.kido.media.session.WatchEventRepository;

/**
 * Shared plumbing behind every "trending this week" rail: three windowed group-by
 * queries, turned into one ranked list via {@link WeeklyPopularityRanker}.
 *
 * <p>Kept apart from both {@link HomeService} and the music tab's own home service —
 * neither owns "this week" exclusively, and each already keeps its rail-building
 * separate from the other's for its own reasons (see {@code MusicHomeDtos}).
 */
public final class TrendingWindow {

    private TrendingWindow() {}

    /**
     * @param types the types to window over — the caller decides how narrow "this
     *              week" means, e.g. films alone rather than every video type
     * @param since the window's start; the caller owns how long "this week" is
     * @param limit how many to return
     */
    public static List<WeeklyPopularityRanker.Scored> rank(MediaItemRepository items,
                                                            WatchEventRepository watchEvents,
                                                            MediaItemLikeRepository likes,
                                                            MediaItemCommentRepository comments,
                                                            List<MediaType> types,
                                                            Instant since,
                                                            int limit) {
        Map<String, Long> views = countsById(watchEvents.viewCountsByItemSince(types, since));
        Map<String, Long> likeCounts = countsById(likes.countsByItemSince(types, since));
        Map<String, Long> commentCounts = countsById(comments.countsByItemSince(types, since));

        // The pool is the union of everything with at least one signal in the window —
        // unlike a lifetime blend there is no monotonic counter to slice a "top
        // hundred" from, since these three counts exist only as this window's own
        // group-by results.
        Set<String> candidateIds = new LinkedHashSet<>();
        candidateIds.addAll(views.keySet());
        candidateIds.addAll(likeCounts.keySet());
        candidateIds.addAll(commentCounts.keySet());
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        return WeeklyPopularityRanker.rank(
                items.findAllById(candidateIds), views, likeCounts, commentCounts, limit);
    }

    private static Map<String, Long> countsById(List<Object[]> rows) {
        Map<String, Long> byId = new LinkedHashMap<>();
        for (Object[] row : rows) {
            byId.put((String) row[0], ((Number) row[1]).longValue());
        }
        return byId;
    }
}
