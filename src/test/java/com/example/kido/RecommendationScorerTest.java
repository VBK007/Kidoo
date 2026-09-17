package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.recommend.RecommendationScorer;
import com.example.kido.media.recommend.RecommendationScorer.Recommended;
import com.example.kido.media.recommend.TasteProfile;
import com.example.kido.media.recommend.TasteProfile.Facet;
import com.example.kido.media.recommend.TasteProfile.FacetKind;

/**
 * The per-person ordering, without a database.
 *
 * <p>Isolated for the same reason {@code PopularityRankerTest} is: the blend is the one
 * part of this feature that is a judgement rather than plumbing, and every property here
 * is a way it could go wrong quietly — a "for you" rail that is really a rating sort
 * looks perfectly plausible from the outside.
 */
class RecommendationScorerTest {

    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    private static MediaItem item(String title, Double rating, Set<String> genres,
                                  String language, Integer year, Instant addedAt) {
        MediaItem built = MediaItem.builder()
                .id(title)
                .title(title)
                .sortTitle(title.toLowerCase())
                .rating(rating)
                .year(year)
                .primaryLanguage(language)
                .addedAt(addedAt)
                .build();
        if (genres != null) {
            built.getGenres().addAll(genres);
        }
        return built;
    }

    private static TasteProfile taste(Map<Facet, Double> weights, Map<Facet, String> evidence) {
        return new TasteProfile("p1", weights, evidence, weights.size());
    }

    private static Facet genre(String value) {
        return new Facet(FacetKind.GENRE, value);
    }

    // --- the point of the feature ---

    /**
     * A title matching what this person watches beats a better-rated one that does not.
     * If this fails the rail is a rating sort wearing a different heading.
     */
    @Test
    void tasteOutweighsRatingAlone() {
        MediaItem mine = item("Mine", 7.0, Set.of("Action"), "ta", 2019, NOW);
        MediaItem acclaimed = item("Acclaimed", 9.2, Set.of("Documentary"), "en", 2019, NOW);

        List<Recommended> ranked = RecommendationScorer.rank(
                List.of(acclaimed, mine),
                taste(Map.of(genre("action"), 1.0,
                        new Facet(FacetKind.LANGUAGE, "ta"), 1.0), Map.of()),
                7.0, NOW, 10);

        assertEquals("Mine", ranked.get(0).item().getTitle());
    }

    /** But taste alone must not carry rubbish: quality is 30% and has to be able to win. */
    @Test
    void qualityStillSeparatesTwoTitlesTheProfileWouldBothLike() {
        MediaItem good = item("Good", 9.0, Set.of("Action"), "ta", 2019, NOW);
        MediaItem bad = item("Bad", 3.0, Set.of("Action"), "ta", 2019, NOW);

        List<Recommended> ranked = RecommendationScorer.rank(List.of(bad, good),
                taste(Map.of(genre("action"), 1.0), Map.of()), 7.0, NOW, 10);

        assertEquals("Good", ranked.get(0).item().getTitle());
    }

    /**
     * A negative facet is a verdict, not an absence: something they walked away from has
     * to place below something they have no opinion about.
     */
    @Test
    void aRejectedFacetPushesATitleDown() {
        MediaItem rejected = item("Rejected", 8.0, Set.of("Horror"), "en", 2019, NOW);
        MediaItem neutral = item("Neutral", 8.0, Set.of("Western"), "en", 2019, NOW);

        List<Recommended> ranked = RecommendationScorer.rank(List.of(rejected, neutral),
                taste(Map.of(genre("horror"), -0.8), Map.of()), 7.0, NOW, 10);

        assertEquals("Neutral", ranked.get(0).item().getTitle());
    }

    /**
     * The mean, not the maximum. One favourite actor in a bit part must not drag a film
     * that is wrong in every other way to the top.
     */
    @Test
    void oneStrongFacetDoesNotCarryATitleThatIsOtherwiseWrong() {
        MediaItem broadMatch = item("BroadMatch", 8.0, Set.of("Action", "Thriller"), "ta",
                2019, NOW);
        MediaItem oneFacet = item("OneFacet", 8.0, Set.of("Opera", "Musical"), "ta", 2019, NOW);

        Map<Facet, Double> weights = new LinkedHashMap<>();
        weights.put(genre("action"), 1.0);
        weights.put(genre("thriller"), 0.9);
        weights.put(new Facet(FacetKind.LANGUAGE, "ta"), 1.0);

        List<Recommended> ranked = RecommendationScorer.rank(
                List.of(oneFacet, broadMatch), taste(weights, Map.of()), 7.0, NOW, 10);

        assertEquals("BroadMatch", ranked.get(0).item().getTitle());
    }

    // --- explanations ---

    /**
     * The feature is the sentence. "Because you watched Kaithi" is checkable; "Recommended
     * for you" is a horoscope.
     */
    @Test
    void aPickNamesTheTitleThatEarnedIt() {
        MediaItem suggestion = item("Vikram", 8.4, Set.of("Action"), "ta", 2022, NOW);

        Recommended scored = RecommendationScorer.score(suggestion,
                taste(Map.of(genre("action"), 1.0), Map.of(genre("action"), "Kaithi")),
                7.0, NOW);

        assertEquals("Because you watched Kaithi", scored.reason());
    }

    /** With a taste but no evidence behind it — a stated preference — say that instead. */
    @Test
    void aStatedPreferenceIsExplainedWithoutInventingAWatchHistory() {
        MediaItem suggestion = item("Vikram", 8.4, Set.of("Action"), "ta", 2022, NOW);

        Recommended scored = RecommendationScorer.score(suggestion,
                taste(Map.of(genre("action"), 1.0), Map.of()), 7.0, NOW);

        assertEquals("Matches your action films", scored.reason());
    }

    /** A title must never be recommended because of itself. */
    @Test
    void aTitleIsNotItsOwnEvidence() {
        MediaItem suggestion = item("Kaithi", 8.4, Set.of("Action"), "ta", 2019, NOW);

        Recommended scored = RecommendationScorer.score(suggestion,
                taste(Map.of(genre("action"), 1.0), Map.of(genre("action"), "Kaithi")),
                7.0, NOW);

        assertNotEquals("Because you watched Kaithi", scored.reason());
    }

    /** With no taste at all, say something true rather than something vague. */
    @Test
    void coldStartPicksAreExplainedHonestly() {
        MediaItem rated = item("Rated", 8.4, Set.of("Action"), "ta", 2019, NOW);
        MediaItem unrated = item("Unrated", null, Set.of("Action"), "ta", 2019, NOW);

        assertEquals("Rated 8.4",
                RecommendationScorer.score(rated, TasteProfile.empty("p1"), 7.0, NOW).reason());
        assertEquals("New to you",
                RecommendationScorer.score(unrated, TasteProfile.empty("p1"), 7.0, NOW).reason());
    }

    // --- the parts ---

    @Test
    void freshnessFavoursSomethingJustAdded() {
        MediaItem fresh = item("Fresh", 8.0, null, null, 2019, NOW.minus(3, ChronoUnit.DAYS));
        MediaItem old = item("Old", 8.0, null, null, 2019, NOW.minus(800, ChronoUnit.DAYS));

        assertEquals(1.0, RecommendationScorer.score(fresh, TasteProfile.empty("p"), 7.0, NOW)
                .freshnessScore());
        assertEquals(0.0, RecommendationScorer.score(old, TasteProfile.empty("p"), 7.0, NOW)
                .freshnessScore());
    }

    /** An unrated title is scored at the library's mean, not at zero. */
    @Test
    void unratedTitlesAreNotPunished() {
        MediaItem unrated = item("Unrated", null, null, null, 2019, NOW);
        assertEquals(0.8, RecommendationScorer.score(unrated, TasteProfile.empty("p"), 8.0, NOW)
                .qualityScore());
    }

    /** The weights have to sum to one, or the score is not on the scale it claims. */
    @Test
    void theWeightsSumToOne() {
        assertEquals(1.0, RecommendationScorer.TASTE_WEIGHT
                + RecommendationScorer.QUALITY_WEIGHT
                + RecommendationScorer.FRESHNESS_WEIGHT, 1e-9);
    }

    /** Two requests seeing the same data must produce the same order. */
    @Test
    void tiesAreBrokenSoTheRailDoesNotReshuffle() {
        MediaItem b = item("Bravo", 8.0, null, null, 2019, NOW);
        MediaItem a = item("Alpha", 8.0, null, null, 2019, NOW);

        List<Recommended> ranked = RecommendationScorer.rank(List.of(b, a),
                TasteProfile.empty("p"), 7.0, NOW, 10);

        assertEquals(List.of("Alpha", "Bravo"),
                ranked.stream().map(r -> r.item().getTitle()).toList());
    }

    @Test
    void anEmptyPoolIsAnEmptyResultRatherThanAFailure() {
        assertTrue(RecommendationScorer.rank(List.of(), TasteProfile.empty("p"), 7.0, NOW, 10)
                .isEmpty());
        assertTrue(RecommendationScorer.rank(null, TasteProfile.empty("p"), 7.0, NOW, 10)
                .isEmpty());
    }
}
