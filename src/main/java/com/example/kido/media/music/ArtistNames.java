package com.example.kido.media.music;

import java.util.LinkedHashSet;
import java.util.Locale;
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

    /**
     * Values that mean "nobody wrote this down", rather than naming a person.
     *
     * <p>Every music library collects them. Taggers write "Unknown Artist", rippers
     * write "Various", and a file with no tags at all falls back to the folder above
     * it — which on a disk copied off a stick is called {@code usb}, and duly gave two
     * hundred and twenty tracks the same imaginary singer and a shelf of their own on
     * the music screen.
     *
     * <p>Deliberately short and literal. Anything cleverer — rejecting bare lowercase
     * words, say — would eventually discard a real artist, and a missing shelf is a
     * far quieter failure than a missing singer.
     */
    private static final Set<String> PLACEHOLDERS = Set.of(
            "usb", "unknown", "unknown artist", "various", "various artists", "va",
            "none", "n/a", "na", "untitled", "audio", "music", "mp3", "track");

    /**
     * True when a credit says nothing: empty, or one of the stand-ins above.
     */
    public static boolean isPlaceholder(String artist) {
        return artist == null
                || artist.isBlank()
                || PLACEHOLDERS.contains(artist.trim().toLowerCase(Locale.ROOT));
    }

    public static Set<String> split(String artist) {
        Set<String> names = new LinkedHashSet<>();
        if (isPlaceholder(artist)) {
            return names;
        }
        for (String part : SEPARATOR.split(artist)) {
            String trimmed = part.trim();
            // Checked per name as well as on the whole, because "A.R. Rahman, Unknown"
            // names one real person and one absence.
            if (!trimmed.isEmpty() && !isPlaceholder(trimmed)) {
                names.add(trimmed);
            }
        }
        return names;
    }
}
