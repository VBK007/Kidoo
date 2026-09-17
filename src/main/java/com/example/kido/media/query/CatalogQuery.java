package com.example.kido.media.query;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.example.kido.common.ApiException;
import com.example.kido.media.catalog.MediaType;

import lombok.Builder;

/**
 * Everything the catalog can be asked for, as one value.
 *
 * <p>The filters existed already — they were arguments to {@code CatalogService.browse}
 * that became {@code Specification}s and died with the request. Nothing could save one,
 * name one, or produce one from something other than query parameters. Three features
 * want exactly that: a smart collection is a query somebody named, a search box is a
 * query parsed out of a sentence, and a recommendation is a query plus a score.
 *
 * <p>So this is deliberately a dumb record. It holds no logic beyond validation:
 * {@link CatalogQuerySpecs} turns it into a database query, and everything that produces
 * one — the browse endpoint, a stored collection, a parser — goes through
 * {@link #validated()} first. A query from a sentence is then exactly as trustworthy as
 * a query from a URL, which is the property that makes a natural-language layer safe to
 * add later: whatever writes the object, the object is all it can say.
 *
 * <p>Absent means "no opinion" for every field. An empty query is the whole browsable
 * library, which is what the grid shows with no chips selected.
 */
@Builder(toBuilder = true)
public record CatalogQuery(

        /** Media types to include; empty means every type. */
        Set<MediaType> types,

        /** Case-insensitive substring of title, sort title or filename. */
        String titleContains,

        Set<String> genres,

        /** Whether {@link #genres} means any of them or all of them. */
        MatchMode genreMatch,

        /** Tagged cast or crew; an item must carry every name listed. */
        Set<String> people,

        /** ISO 639-1 codes, as {@link com.example.kido.media.metadata.Languages} writes them. */
        Set<String> languages,

        /**
         * Match only the first audio track rather than any of them — "a Tamil film"
         * rather than "a film with a Tamil track somewhere".
         */
        boolean primaryLanguageOnly,

        Range year,
        Range runtimeMinutes,
        Range rating,

        /** Minimum vertical resolution; 2160 is the client's 4K chip. */
        Integer minHeight,

        /** Whose watch state to filter on, if anyone's. */
        WatchedBy watched,

        /** True for items this profile has liked, false for ones it has not. */
        Boolean liked,

        /** A key from {@link CatalogSort}; null sorts by newest added. */
        String sort) {

    /** How a multi-value filter combines. */
    public enum MatchMode {
        /** Any one of the values is enough. */
        ANY,
        /** Every value must be present. */
        ALL
    }

    /**
     * Which watch state to keep.
     *
     * <p>Per-profile and per-household are both here because both are asked for and
     * they are different questions: "I haven't seen it" is what the client's UNWATCHED
     * chip means, while "nobody here has seen it" is what makes a Never-watched
     * collection worth showing to a household.
     */
    public enum WatchedBy {
        /** No filter. */
        ANYONE,
        /** This profile has finished it. */
        ME,
        /** This profile has not finished it. */
        NOT_ME,
        /** Someone in the household has finished it. */
        SOMEONE,
        /** Nobody in the household has finished it. */
        NOBODY
    }

    /**
     * An inclusive bound, either end optional.
     *
     * <p>Numbers rather than a generic, so it serialises without type information: this
     * record is stored as JSON in a collection row and produced as JSON by a parser, and
     * a generic bound would need a type tag in both places to come back as itself.
     * {@code Double} covers year and runtime as well as rating — losing precision would
     * take a year beyond 2^53.
     */
    public record Range(Double min, Double max) {

        public static Range atLeast(double min) {
            return new Range(min, null);
        }

        public static Range atMost(double max) {
            return new Range(null, max);
        }

        public static Range between(double min, double max) {
            return new Range(min, max);
        }

        public boolean isEmpty() {
            return min == null && max == null;
        }

        /** True when the bounds cross, which no item could ever satisfy. */
        public boolean isImpossible() {
            return min != null && max != null && min > max;
        }
    }

    /**
     * The same query with junk removed and contradictions rejected.
     *
     * <p>Every producer runs this, so what reaches {@link CatalogQuerySpecs} is already
     * sane regardless of who wrote it. Blank strings are dropped rather than rejected —
     * a client sending {@code &genre=} means "no genre filter", not a bad request — but
     * a range whose bounds cross is a mistake that would silently return nothing, and
     * saying so is more useful than an empty grid.
     */
    public CatalogQuery validated() {
        reject(year, "year");
        reject(runtimeMinutes, "runtime");
        reject(rating, "rating");

        if (minHeight != null && minHeight < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "minHeight cannot be negative");
        }
        if (sort != null) {
            // Throws on an unknown key, so a bad sort fails here rather than at the
            // database, where the message would name a column instead of a parameter.
            CatalogSort.of(sort);
        }

        return toBuilder()
                .types(emptyToNull(types))
                .titleContains(blankToNull(titleContains))
                .genres(cleanFacet(genres))
                .genreMatch(genreMatch == null ? MatchMode.ANY : genreMatch)
                .people(cleanFacet(people))
                .languages(cleanFacet(languages))
                .year(blankToNull(year))
                .runtimeMinutes(blankToNull(runtimeMinutes))
                .rating(blankToNull(rating))
                .watched(watched == null ? WatchedBy.ANYONE : watched)
                .build();
    }

    private static void reject(Range range, String name) {
        if (range != null && range.isImpossible()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Impossible " + name + " range: " + range.min() + " is above " + range.max());
        }
    }

    /**
     * Genres, people and languages are compared lowercased in SQL, so they are
     * lowercased here instead — once, when the query is built, rather than per row.
     */
    private static Set<String> cleanFacet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static Set<MediaType> emptyToNull(Set<MediaType> values) {
        return values == null || values.isEmpty() ? null : values;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Range blankToNull(Range range) {
        return range == null || range.isEmpty() ? null : range;
    }
}
