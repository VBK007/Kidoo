package com.example.kido.media.query;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import com.example.kido.common.ApiException;

/**
 * The orders a catalog listing can be asked for.
 *
 * <p>Lifted out of {@code CatalogService} so a {@link CatalogQuery} can carry one: a
 * saved collection is a filter *and* an order — "recently added 4K" and "the oldest 4K
 * I own" are the same filter and different lists — and an order that only exists as a
 * string parsed inside one method cannot be stored with the filter it belongs to.
 *
 * <p>Every order ends on a unique-enough column so a listing does not reshuffle between
 * two requests that see the same data. Sorting on {@code rating} alone would leave every
 * unrated title in whatever order the database felt like, and page 2 could then repeat a
 * row from page 1.
 */
public enum CatalogSort {

    /**
     * Newest first, and the default: a film that landed on the disk this morning
     * should be on the first page rather than buried alphabetically.
     */
    ADDED("added", Sort.by(Sort.Order.desc("addedAt"), Sort.Order.asc("sortTitle"))),

    TITLE("title", Sort.by(Sort.Order.asc("sortTitle"))),

    /** For the home-video timeline, where the shoot date is the only date that means anything. */
    CAPTURED("captured", Sort.by(Sort.Order.desc("capturedAt").nullsLast(),
            Sort.Order.asc("sortTitle"))),

    YEAR("year", Sort.by(Sort.Order.desc("year").nullsLast(), Sort.Order.asc("sortTitle"))),

    RATING("rating", Sort.by(Sort.Order.desc("rating").nullsLast(),
            Sort.Order.asc("sortTitle"))),

    LIKES("likes", Sort.by(Sort.Order.desc("likeCount"), Sort.Order.asc("sortTitle")));

    // No "views" here on purpose: a play count is the sum of two columns, and a Sort
    // cannot express that. Most-watched ordering lives on the home screen's rail, which
    // sorts on the sum in JPQL.

    private final String key;
    private final Sort sort;

    CatalogSort(String key, Sort sort) {
        this.key = key;
        this.sort = sort;
    }

    public String key() {
        return key;
    }

    public Sort sort() {
        return sort;
    }

    /** The default, named rather than implied so callers can say they mean it. */
    public static CatalogSort defaultSort() {
        return ADDED;
    }

    /**
     * @param key a wire value such as {@code rating}; null gives the default
     * @throws ApiException 400 naming the keys that do exist, because the caller sent a
     *                      parameter and should be told about the parameter — not about
     *                      a column it has never heard of
     */
    public static CatalogSort of(String key) {
        if (key == null || key.isBlank()) {
            return defaultSort();
        }
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        for (CatalogSort candidate : values()) {
            if (candidate.key.equals(wanted)) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST,
                "Unknown sort '" + key + "' (expected " + keys() + ")");
    }

    private static String keys() {
        return Arrays.stream(values()).map(CatalogSort::key).collect(Collectors.joining(", "));
    }
}
