package com.example.kido.poster;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;

import com.example.kido.common.ApiException;

/**
 * The ceremonies a poster can be for.
 *
 * <p>A closed set rather than a free-text column: the category is what the picker on
 * the phone is built from and what {@code GET /api/poster/templates/{category}} routes
 * on, so a typo saved once ("Marraige") would create a shelf nobody can reach and
 * nobody can see is empty.
 */
public enum PosterCategory {

    MARRIAGE,
    ENGAGEMENT,
    BIRTHDAY,
    BABY_SHOWER,
    NAMING_CEREMONY,
    HOUSE_WARMING,
    ANNIVERSARY;

    /**
     * Reads a category the way a client is likely to have written it.
     *
     * <p>Accepts the enum name, the URL form and the spoken form — {@code BABY_SHOWER},
     * {@code baby-shower} and {@code "baby shower"} are the same ceremony. The path
     * variant is the one the app actually sends, and refusing it on case alone would be
     * a 400 that reads like a server bug.
     *
     * @throws ApiException 400, naming every value, so a client can correct itself
     *                      without reading the source
     */
    public static PosterCategory parse(String raw) {
        if (raw != null) {
            String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (PosterCategory category : values()) {
                if (category.name().equals(normalised)) {
                    return category;
                }
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST,
                "Unknown category '" + raw + "'. Expected one of: " + names());
    }

    /** The URL form: {@code BABY_SHOWER} → {@code baby-shower}. */
    public String slug() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static String names() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
