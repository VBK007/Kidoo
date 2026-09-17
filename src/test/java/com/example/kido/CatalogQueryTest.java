package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.kido.common.ApiException;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.query.CatalogQuery.MatchMode;
import com.example.kido.media.query.CatalogQuery.Range;
import com.example.kido.media.query.CatalogQuery.WatchedBy;
import com.example.kido.media.query.CatalogSort;

/**
 * The query object's own rules, tested without a database.
 *
 * <p>Validation is the load-bearing part: every producer runs it, and the reason a
 * natural-language layer can be added later without widening the attack surface is that
 * a query built from a sentence has to survive exactly the same checks as one built
 * from a URL. So the checks are worth pinning down on their own.
 */
class CatalogQueryTest {

    /**
     * Facets are compared lowercased in SQL, so they are lowercased once here instead
     * of per row.
     */
    @Test
    void facetsAreNormalisedOnce() {
        CatalogQuery query = CatalogQuery.builder()
                .genres(Set.of("Action", "  SCI-FI  "))
                .people(Set.of("Rajinikanth"))
                .languages(Set.of("TA"))
                .build()
                .validated();

        assertEquals(Set.of("action", "sci-fi"), query.genres());
        assertEquals(Set.of("rajinikanth"), query.people());
        assertEquals(Set.of("ta"), query.languages());
    }

    /** A client sending {@code &genre=} means "no genre filter", not a bad request. */
    @Test
    void blanksAreDroppedRatherThanRejected() {
        CatalogQuery query = CatalogQuery.builder()
                .titleContains("   ")
                .genres(Set.of("", "  "))
                .types(Set.of())
                .build()
                .validated();

        assertNull(query.titleContains());
        assertNull(query.genres());
        assertNull(query.types());
    }

    /**
     * A crossed range matches nothing, so returning an empty grid would look like a
     * library with no such films rather than a request with a typo in it.
     */
    @Test
    void impossibleRangesAreRejectedRatherThanReturningNothing() {
        ApiException thrown = assertThrows(ApiException.class, () -> CatalogQuery.builder()
                .year(Range.between(2020, 2010))
                .build()
                .validated());

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatus());
        assertTrue(thrown.getMessage().toLowerCase().contains("year"), thrown.getMessage());
    }

    @Test
    void openEndedRangesAreFine() {
        CatalogQuery query = CatalogQuery.builder()
                .runtimeMinutes(Range.atMost(120))
                .rating(Range.atLeast(8))
                .build()
                .validated();

        assertNull(query.runtimeMinutes().min());
        assertEquals(120, query.runtimeMinutes().max());
        assertEquals(8, query.rating().min());
    }

    /** An empty range says nothing and should not become a predicate. */
    @Test
    void emptyRangesAreDropped() {
        CatalogQuery query = CatalogQuery.builder()
                .year(new Range(null, null))
                .build()
                .validated();
        assertNull(query.year());
    }

    /** Defaults are filled in explicitly, so the spec builder never reads a null mode. */
    @Test
    void defaultsAreResolvedDuringValidation() {
        CatalogQuery query = CatalogQuery.builder().genres(Set.of("action")).build().validated();

        assertEquals(MatchMode.ANY, query.genreMatch());
        assertEquals(WatchedBy.ANYONE, query.watched());
    }

    /**
     * A bad sort fails where the caller can act on it. Letting it through would surface
     * as a database error naming a column the caller never wrote.
     */
    @Test
    void anUnknownSortIsRejectedAtValidation() {
        ApiException thrown = assertThrows(ApiException.class,
                () -> CatalogQuery.builder().sort("nonsense").build().validated());
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatus());
    }

    @Test
    void negativeHeightIsRejected() {
        assertThrows(ApiException.class,
                () -> CatalogQuery.builder().minHeight(-1).build().validated());
    }

    /** Media types are enums already; they only need emptiness collapsed. */
    @Test
    void typesSurviveValidationIntact() {
        CatalogQuery query = CatalogQuery.builder()
                .types(Set.of(MediaType.FILM, MediaType.ANIME))
                .build()
                .validated();
        assertEquals(Set.of(MediaType.FILM, MediaType.ANIME), query.types());
    }

    // --- sort keys ---

    @Test
    void sortKeysResolveCaseInsensitivelyAndDefaultToNewestFirst() {
        assertEquals(CatalogSort.RATING, CatalogSort.of("RaTiNg"));
        assertEquals(CatalogSort.ADDED, CatalogSort.of(null));
        assertEquals(CatalogSort.ADDED, CatalogSort.of("  "));
        assertEquals(CatalogSort.ADDED, CatalogSort.defaultSort());
    }

    /** The message has to name the keys that exist, or it cannot be acted on. */
    @Test
    void anUnknownSortNamesTheOnesThatExist() {
        ApiException thrown = assertThrows(ApiException.class, () -> CatalogSort.of("views"));
        assertTrue(thrown.getMessage().contains("title"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("rating"), thrown.getMessage());
    }

    /**
     * Every order ends on a unique-enough column, or page 2 can repeat a row from page
     * 1 whenever the primary key ties.
     */
    @Test
    void everySortIsDeterministic() {
        for (CatalogSort sort : CatalogSort.values()) {
            long orders = sort.sort().stream().count();
            assertTrue(orders >= 1, sort.key());
            String last = sort.sort().stream().reduce((a, b) -> b).orElseThrow().getProperty();
            assertTrue(last.equals("sortTitle"),
                    sort.key() + " must break ties on sortTitle but ends on " + last);
        }
    }
}
