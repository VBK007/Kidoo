package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.home.PopularityRanker;
import com.example.kido.media.home.PopularityRanker.Scored;

/**
 * The home screen's ordering, tested without a database.
 *
 * <p>Worth isolating: the blend is the one piece of this feature that is a judgement
 * rather than plumbing, and every property asserted here is a way it could go wrong
 * quietly — a rail that looks plausible while being sorted by only one of its three
 * signals is not something an integration test would catch.
 */
class PopularityRankerTest {

    private static MediaItem item(String title, Double rating, long plays, long likes) {
        return MediaItem.builder()
                .id(title)
                .title(title)
                .sortTitle(title.toLowerCase())
                .rating(rating)
                .directPlayCount(plays)
                .transcodeCount(0)
                .likeCount(likes)
                .build();
    }

    @Test
    void allThreeSignalsMove() {
        MediaItem best = item("Best", 9.0, 40, 6);
        MediaItem middling = item("Middling", 6.0, 5, 1);
        MediaItem worst = item("Worst", 3.0, 0, 0);

        List<Scored> ranked = PopularityRanker.rank(List.of(worst, middling, best), 6.0, 10);

        assertEquals(List.of("Best", "Middling", "Worst"),
                ranked.stream().map(s -> s.item().getTitle()).toList());
        assertTrue(ranked.get(0).score() > ranked.get(1).score());
    }

    /** A title nobody has touched must not outrank one the house watches, on rating alone. */
    @Test
    void watchingBeatsAMarginallyBetterRating() {
        MediaItem watched = item("Watched", 7.5, 60, 0);
        MediaItem unwatchedButRated = item("Pristine", 8.2, 0, 0);

        List<Scored> ranked =
                PopularityRanker.rank(List.of(unwatchedButRated, watched), 7.0, 10);

        assertEquals("Watched", ranked.get(0).item().getTitle());
    }

    /** Likes are the deliberate signal, and must be able to carry a title on their own. */
    @Test
    void likesLiftATitleWithNothingElseGoingForIt() {
        MediaItem liked = item("Liked", 6.0, 2, 5);
        MediaItem ignored = item("Ignored", 6.0, 2, 0);

        List<Scored> ranked = PopularityRanker.rank(List.of(ignored, liked), 6.0, 10);

        assertEquals("Liked", ranked.get(0).item().getTitle());
        assertTrue(ranked.get(0).likeScore() > ranked.get(1).likeScore());
    }

    /**
     * The reason the counts are log-scaled: one runaway favourite must not flatten the
     * rest of the rail to zero, or the blend collapses into a rating sort.
     */
    @Test
    void oneRunawayFavouriteDoesNotFlattenTheRest() {
        MediaItem runaway = item("Runaway", 6.0, 500, 0);
        MediaItem respectable = item("Respectable", 6.0, 50, 0);

        List<Scored> ranked = PopularityRanker.rank(List.of(runaway, respectable), 6.0, 10);
        double gap = ranked.get(0).viewScore() - ranked.get(1).viewScore();

        assertEquals("Runaway", ranked.get(0).item().getTitle());
        assertTrue(gap < 0.4, "ten times the plays should not be ten times the score, gap=" + gap);
    }

    /** Most home footage has no sidecar rating; scoring that as zero would bury it. */
    @Test
    void unratedTitlesAreScoredAtTheLibraryMean() {
        MediaItem unrated = item("Unrated", null, 0, 0);
        MediaItem atMean = item("AtMean", 7.0, 0, 0);

        List<Scored> ranked = PopularityRanker.rank(List.of(unrated, atMean), 7.0, 10);

        assertEquals(ranked.get(0).ratingScore(), ranked.get(1).ratingScore(), 0.0001);
    }

    /** A rail that reshuffles between two identical requests looks broken. */
    @Test
    void tiesBreakOnTitleSoTheRailIsStable() {
        MediaItem a = item("Alpha", 7.0, 3, 1);
        MediaItem b = item("Beta", 7.0, 3, 1);

        assertEquals("Alpha", PopularityRanker.rank(List.of(b, a), 7.0, 10)
                .get(0).item().getTitle());
        assertEquals("Alpha", PopularityRanker.rank(List.of(a, b), 7.0, 10)
                .get(0).item().getTitle());
    }

    @Test
    void limitTruncatesAndEmptyPoolIsEmpty() {
        List<Scored> ranked = PopularityRanker.rank(
                List.of(item("A", 9.0, 1, 1), item("B", 8.0, 1, 1), item("C", 7.0, 1, 1)),
                8.0, 2);

        assertEquals(2, ranked.size());
        assertTrue(PopularityRanker.rank(List.of(), 8.0, 10).isEmpty());
    }

    /**
     * The subtitle a poster shows has to match the signal that actually placed it.
     *
     * <p>Ranked as one pool, because a signal is only strong relative to the rest of
     * the library: scored alone, every item leads on everything.
     */
    @Test
    void reasonNamesTheSignalThatCarriedTheTitle() {
        MediaItem liked = item("Liked", 5.0, 1, 9);
        MediaItem played = item("Played", 5.0, 30, 0);
        MediaItem rated = item("Rated", 9.1, 1, 0);

        List<Scored> ranked = PopularityRanker.rank(List.of(liked, played, rated), 5.0, 10);
        var reasons = ranked.stream().collect(java.util.stream.Collectors.toMap(
                s -> s.item().getTitle(), Scored::reason));

        assertEquals("Liked by 9 in your house", reasons.get("Liked"));
        assertEquals("Played 30 times", reasons.get("Played"));
        assertEquals("Rated 9.1", reasons.get("Rated"));
    }
}
