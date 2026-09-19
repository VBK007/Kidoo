package com.example.kido.media.music;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits a track's raw, already-cleaned artist credit into the individual singers it
 * names — {@code "Anirudh Ravichander, Badshah"} is two artists, each entitled to their
 * own tile and their own full catalog, not a third "artist" that is really just this one
 * credit line. See {@link com.example.kido.media.catalog.MediaItem#getArtistNames()}.
 */
public final class ArtistNames {

    private ArtistNames() {}

    /** Comma, ampersand, slash, or an English "feat./ft./featuring/with" connector. */
    private static final Pattern SEPARATOR = Pattern.compile(
            "\\s*,\\s*|\\s*&\\s*|\\s*/\\s*"
                    + "|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+|\\s+with\\s+",
            Pattern.CASE_INSENSITIVE);

    public static Set<String> split(String artist) {
        Set<String> names = new LinkedHashSet<>();
        if (artist == null || artist.isBlank()) {
            return names;
        }
        for (String part : SEPARATOR.split(artist)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }
}
