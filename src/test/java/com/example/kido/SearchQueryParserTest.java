package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.query.CatalogQuery.WatchedBy;
import com.example.kido.media.search.SearchQueryParser;
import com.example.kido.media.search.SearchQueryParser.Parsed;
import com.example.kido.media.search.SearchVocabulary;

/**
 * The grammar, without a database.
 *
 * <p>This is the feature. Every phrasing that fails to parse is a search that silently
 * does something else — usually a title match on words that were meant as filters — and
 * nothing about the response looks wrong when it happens. So the cases here are the
 * phrasings people actually type, not the ones the regexes were written for.
 */
class SearchQueryParserTest {

    private static final SearchVocabulary VOCABULARY = SearchVocabulary.of(
            List.of("Action", "Comedy", "Science Fiction", "Thriller", "Drama"),
            List.of("Rajinikanth", "Kristen Stewart", "Christopher Nolan"),
            List.of("ta", "en", "hi"));

    private static Parsed parse(String text) {
        return SearchQueryParser.parse(text, VOCABULARY);
    }

    private static CatalogQuery query(String text) {
        return parse(text).query();
    }

    // --- the sentence from the brief ---

    /** The example this whole stage was specified against. */
    @Test
    void theHeadlineSentenceParsesCompletely() {
        CatalogQuery parsed = query("Show me Tamil movies under 2 hours with rating > 8");

        assertEquals(Set.of("ta"), parsed.languages());
        assertTrue(parsed.primaryLanguageOnly(), "a language typed into a search box "
                + "means the film's own language, not a dub it happens to carry");
        assertEquals(120, parsed.runtimeMinutes().max());
        assertEquals(8.0, parsed.rating().min());
        // Nothing left over: "show me" and "movies" are noise, not a title.
        assertNull(parsed.titleContains());
    }

    // --- runtime ---

    @Test
    void runtimeIsUnderstoodInHoursAndMinutes() {
        assertEquals(120, query("under 2 hours").runtimeMinutes().max());
        assertEquals(120, query("under two hours").runtimeMinutes().max());
        assertEquals(90, query("less than 90 minutes").runtimeMinutes().max());
        assertEquals(90, query("no longer than 90 mins").runtimeMinutes().max());
        assertEquals(120, query("2 hours or less").runtimeMinutes().max());
        assertEquals(180, query("over 3 hours").runtimeMinutes().min());
        assertEquals(150, query("at least 150 minutes").runtimeMinutes().min());
    }

    /** Hours are converted on the way in, so the query carries one unit. */
    @Test
    void hoursBecomeMinutesInTheQuery() {
        assertEquals(90, query("under 1.5 hours").runtimeMinutes().max());
    }

    // --- rating ---

    @Test
    void ratingIsUnderstoodHoweverItIsPhrased() {
        assertEquals(8.0, query("rating above 8").rating().min());
        assertEquals(8.0, query("rating > 8").rating().min());
        assertEquals(8.0, query("imdb over 8").rating().min());
        assertEquals(8.0, query("rated better than 8").rating().min());
        assertEquals(8.0, query("8+ rating").rating().min());
        assertEquals(8.5, query("score of at least 8.5").rating().min());
        assertEquals(5.0, query("rating below 5").rating().max());
    }

    /** Somebody who does not give a number still means something specific. */
    @Test
    void highlyRatedMeansAThreshold() {
        assertEquals(8.0, query("highly rated films").rating().min());
        assertEquals(8.0, query("well reviewed").rating().min());
    }

    // --- years ---

    @Test
    void decadesAndYearsAreUnderstood() {
        assertEquals(1990, query("from the 90s").year().min());
        assertEquals(1999, query("from the 90s").year().max());
        assertEquals(1990, query("1990s films").year().min());
        assertEquals(2020, query("the 20s").year().min());
        assertEquals(2015, query("from 2015").year().min());
        assertEquals(2015, query("from 2015").year().max());
        assertEquals(1999, query("before 2000").year().max());
        assertEquals(2011, query("after 2010").year().min());
    }

    /**
     * "the 90s" is the nineteen-nineties and "the 20s" is the twenty-twenties. Nobody
     * browsing a home library means the silent era.
     */
    @Test
    void twoDigitDecadesPickTheCenturyPeopleMean() {
        assertEquals(1980, query("80s action").year().min());
        assertEquals(2000, query("00s comedy").year().min());
        assertEquals(2010, query("10s thriller").year().min());
    }

    // --- watch state, the one that must not be read backwards ---

    @Test
    void watchStateDistinguishesTheHouseholdFromThePerson() {
        assertEquals(WatchedBy.NOBODY, query("films nobody has watched").watched());
        assertEquals(WatchedBy.NOBODY, query("never watched").watched());
        assertEquals(WatchedBy.NOT_ME, query("unwatched films").watched());
        assertEquals(WatchedBy.NOT_ME, query("stuff i haven't seen").watched());
        assertEquals(WatchedBy.SOMEONE, query("films we watched").watched());
        assertEquals(WatchedBy.ME, query("things i've already seen").watched());
    }

    /**
     * The ordering trap: "never watched" contains "watched", so the general rule running
     * first would read the sentence as its own opposite.
     */
    @Test
    void neverWatchedIsNotReadAsWatched() {
        assertEquals(WatchedBy.NOBODY, query("never watched action films").watched());
        assertEquals(WatchedBy.NOT_ME, query("not yet watched").watched());
    }

    // --- vocabulary ---

    @Test
    void genresPeopleAndLanguagesComeFromTheLibrary() {
        CatalogQuery parsed = query("action films with rajinikanth in tamil");

        assertEquals(Set.of("action"), parsed.genres());
        assertEquals(Set.of("rajinikanth"), parsed.people());
        assertEquals(Set.of("ta"), parsed.languages());
    }

    /** A word the library has never heard of cannot become a filter for it. */
    @Test
    void aGenreTheLibraryDoesNotHaveIsNotInvented() {
        CatalogQuery parsed = query("acton films");
        assertNull(parsed.genres());
        // It falls through to a title search, which is visible and dismissible.
        assertEquals("acton", parsed.titleContains());
    }

    /**
     * Longest first, or a multi-word name is half-consumed and its remainder becomes a
     * title search that matches nothing.
     */
    @Test
    void longerPhrasesWinOverTheirOwnPrefixes() {
        CatalogQuery byName = query("films with kristen stewart");
        assertEquals(Set.of("kristen stewart"), byName.people());
        assertNull(byName.titleContains(), "the surname must not leak into a title search");

        CatalogQuery byGenre = query("science fiction");
        assertEquals(Set.of("science fiction"), byGenre.genres());
    }

    @Test
    void aLanguageIsAcceptedByNameOrByCode() {
        assertEquals(Set.of("ta"), query("tamil films").languages());
        assertEquals(Set.of("ta"), query("films in ta").languages());
    }

    // --- everything else ---

    @Test
    void qualityChipsAreUnderstood() {
        assertEquals(2160, query("4k films").minHeight());
        assertEquals(2160, query("uhd").minHeight());
        assertEquals(1080, query("1080p").minHeight());
        assertEquals(720, query("hd films").minHeight());
    }

    @Test
    void typesAreUnderstood() {
        assertEquals(Set.of(MediaType.ANIME), query("anime").types());
        assertEquals(Set.of(MediaType.HOME_VIDEO), query("our home videos").types());
        assertEquals(Set.of(MediaType.PHOTO), query("photos").types());
    }

    @Test
    void orderingWordsSetTheSort() {
        assertEquals("added", query("newest action films").sort());
        assertEquals("rating", query("best action films").sort());
        assertEquals("likes", query("most liked").sort());
    }

    @Test
    void likedIsUnderstood() {
        assertEquals(Boolean.TRUE, query("films i liked").liked());
        assertEquals(Boolean.TRUE, query("my favourites").liked());
    }

    // --- what it does not understand ---

    /** Leftover words are a title search — a guess, but a visible one. */
    @Test
    void unrecognisedWordsBecomeATitleSearch() {
        assertEquals("inception", query("show me inception").titleContains());
        assertEquals("inception", query("inception").titleContains());
    }

    /** Filler must not become a title search, or every sentence finds nothing. */
    @Test
    void noiseWordsAreNotMistakenForATitle() {
        assertNull(query("show me some films to watch").titleContains());
        assertNull(query("find me anything").titleContains());
    }

    @Test
    void anEmptyQueryIsNotAFailure() {
        Parsed parsed = parse("");
        assertTrue(parsed.terms().isEmpty());
        assertTrue(parsed.understoodNothing());
        assertNull(SearchQueryParser.parse(null, VOCABULARY).query().titleContains());
    }

    /** A sentence nothing matched is the case a model-backed parser would take over. */
    @Test
    void understoodNothingIsReportedRatherThanHidden() {
        assertTrue(parse("").understoodNothing());
        assertTrue(!parse("action films").understoodNothing());
        // A bare title is still something understood — it became a filter.
        assertTrue(!parse("inception").understoodNothing());
    }

    // --- the parse is shown, not just applied ---

    /**
     * Every term comes back with the words it was read from, so a client can draw chips
     * and a person can see what was consumed.
     */
    @Test
    void everyTermCarriesWhatItWasReadFrom() {
        Parsed parsed = parse("tamil films under 2 hours rated over 8");

        assertEquals(3, parsed.terms().size(), parsed.terms().toString());
        assertTrue(parsed.terms().stream()
                .anyMatch(term -> term.field().equals("language")
                        && term.label().equals("Tamil")
                        && term.matched().equals("tamil")));
        assertTrue(parsed.terms().stream()
                .anyMatch(term -> term.field().equals("runtime")
                        && term.label().equals("Under 2 hours")));
        assertTrue(parsed.terms().stream()
                .anyMatch(term -> term.field().equals("rating")
                        && term.label().equals("Rated 8+")));
    }

    /** Punctuation and casing are how people type, not a reason to fail. */
    @Test
    void casingAndPunctuationDoNotMatter() {
        CatalogQuery parsed = query("TAMIL films, under 2 hours!");
        assertEquals(Set.of("ta"), parsed.languages());
        assertEquals(120, parsed.runtimeMinutes().max());
    }

    /** A parse has to produce a query the rest of the system already accepts. */
    @Test
    void theResultIsAlwaysAValidatedQuery() {
        for (String sentence : List.of("", "action", "under 2 hours", "rubbish words here",
                "tamil action films from the 90s rated over 8 that nobody has watched")) {
            // validated() throws on anything impossible; parse() runs it before returning.
            assertTrue(SearchQueryParser.parse(sentence, VOCABULARY).query() != null, sentence);
        }
    }
}
