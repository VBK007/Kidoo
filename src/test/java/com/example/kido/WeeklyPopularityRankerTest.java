package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.home.WeeklyPopularityRanker;
import com.example.kido.media.home.WeeklyPopularityRanker.Scored;

/**
 * The "top of the week" ordering, tested without a database — same reason {@link
 * PopularityRankerTest} isolates the lifetime blend: this is the one place the
 * three windowed signals get combined into a single order.
 */
class WeeklyPopularityRankerTest {

    private static MediaItem item(String title) {
        return MediaItem.builder().id(title).title(title).sortTitle(title.toLowerCase()).build();
    }

    @Test
    void moreViewsThisWeekOutranksFewer() {
        MediaItem hot = item("Hot");
        MediaItem cold = item("Cold");

        List<Scored> ranked = WeeklyPopularityRanker.rank(
                List.of(cold, hot),
                Map.of("Hot", 40L, "Cold", 2L),
                Map.of(), Map.of(), 10);

        assertEquals("Hot", ranked.get(0).item().getTitle());
    }

    /** A title with no views but a busy comment thread must still be able to place. */
    @Test
    void commentsCanCarryATitleWithNoViews() {
        MediaItem discussed = item("Discussed");
        MediaItem silent = item("Silent");

        List<Scored> ranked = WeeklyPopularityRanker.rank(
                List.of(silent, discussed),
                Map.of(), Map.of(), Map.of("Discussed", 12L), 10);

        assertEquals("Discussed", ranked.get(0).item().getTitle());
    }

    @Test
    void oneRunawayTitleDoesNotFlattenTheRest() {
        MediaItem runaway = item("Runaway");
        MediaItem respectable = item("Respectable");

        List<Scored> ranked = WeeklyPopularityRanker.rank(
                List.of(runaway, respectable),
                Map.of("Runaway", 500L, "Respectable", 50L),
                Map.of(), Map.of(), 10);

        assertTrue(ranked.get(0).score() - ranked.get(1).score() < 0.4);
    }

    @Test
    void tiesBreakOnTitleSoTheRailIsStable() {
        MediaItem a = item("Alpha");
        MediaItem b = item("Beta");
        Map<String, Long> views = Map.of("Alpha", 3L, "Beta", 3L);

        assertEquals("Alpha",
                WeeklyPopularityRanker.rank(List.of(b, a), views, Map.of(), Map.of(), 10)
                        .get(0).item().getTitle());
        assertEquals("Alpha",
                WeeklyPopularityRanker.rank(List.of(a, b), views, Map.of(), Map.of(), 10)
                        .get(0).item().getTitle());
    }

    @Test
    void limitTruncatesAndEmptyPoolIsEmpty() {
        List<Scored> ranked = WeeklyPopularityRanker.rank(
                List.of(item("A"), item("B"), item("C")),
                Map.of("A", 3L, "B", 2L, "C", 1L), Map.of(), Map.of(), 2);

        assertEquals(2, ranked.size());
        assertTrue(WeeklyPopularityRanker.rank(List.of(), Map.of(), Map.of(), Map.of(), 10).isEmpty());
    }

    @Test
    void reasonNamesViewsFirstWhenPresent() {
        MediaItem watched = item("Watched");
        Scored scored = WeeklyPopularityRanker.rank(
                List.of(watched), Map.of("Watched", 5L), Map.of("Watched", 9L), Map.of(), 10).get(0);

        assertEquals("Watched 5 times this week", scored.reason());
    }
}
