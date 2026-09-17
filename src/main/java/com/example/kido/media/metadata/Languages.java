package com.example.kido.media.metadata;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns whatever an encoder wrote into a language tag into one canonical code.
 *
 * <p>The same language reaches this server spelled several ways, because nothing
 * enforces a spelling: ffprobe passes through the container's own tag, and a file
 * muxed by one tool says {@code tam} where another says {@code ta} and a third says
 * {@code Tamil}. Stored as-is they are three different languages, and "Tamil films"
 * finds a third of them.
 *
 * <p>The mapping is the JDK's own ISO tables rather than a hand-written list — it
 * already knows every two- and three-letter code and every English name, and it stays
 * correct without anyone maintaining it. Only the bibliographic three-letter codes are
 * written out below, because {@link Locale#getISO3Language()} returns the
 * terminological code alone and real files carry both.
 *
 * <p>Canonical form is the two-letter ISO 639-1 code. Two letters because that is what
 * the shorter list can always express and the longer one cannot: a language with no
 * 639-1 code keeps whatever it arrived as, lowercased, so it is still groupable even
 * though it cannot be normalised.
 */
public final class Languages {

    /**
     * What ffprobe writes when the container says nothing. Not a language, and storing
     * it would give every untagged file a shared "und" facet that reads like one.
     */
    private static final Set<String> UNKNOWN =
            Set.of("und", "unknown", "none", "null", "zxx", "mis", "mul");

    /**
     * Bibliographic codes, which {@link Locale} does not map. The list is closed —
     * ISO 639-2 defines both forms for these twenty languages and no others.
     */
    private static final Map<String, String> BIBLIOGRAPHIC = Map.ofEntries(
            Map.entry("alb", "sq"), Map.entry("arm", "hy"), Map.entry("baq", "eu"),
            Map.entry("bur", "my"), Map.entry("chi", "zh"), Map.entry("cze", "cs"),
            Map.entry("dut", "nl"), Map.entry("fre", "fr"), Map.entry("geo", "ka"),
            Map.entry("ger", "de"), Map.entry("gre", "el"), Map.entry("ice", "is"),
            Map.entry("mac", "mk"), Map.entry("mao", "mi"), Map.entry("may", "ms"),
            Map.entry("per", "fa"), Map.entry("rum", "ro"), Map.entry("slo", "sk"),
            Map.entry("tib", "bo"), Map.entry("wel", "cy"));

    /** Every spelling the JDK knows, resolved once: code, ISO3 and English name. */
    private static final Map<String, String> BY_SPELLING = buildIndex();

    private Languages() {}

    private static Map<String, String> buildIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        for (String code : Locale.getISOLanguages()) {
            Locale locale = Locale.of(code);
            index.put(code, code);
            String iso3 = locale.getISO3Language();
            if (!iso3.isEmpty()) {
                index.putIfAbsent(iso3, code);
            }
            String english = locale.getDisplayLanguage(Locale.ENGLISH);
            if (!english.isEmpty()) {
                index.putIfAbsent(english.toLowerCase(Locale.ROOT), code);
            }
        }
        index.putAll(BIBLIOGRAPHIC);
        return Map.copyOf(index);
    }

    /**
     * The canonical code for one tag, or empty when it names no language.
     *
     * <p>A tag that is not in the ISO tables at all is kept rather than dropped: it is
     * still a consistent label, so the files carrying it still group together, and
     * losing them entirely would be worse than grouping them under an odd name.
     *
     * @param raw a tag as it appears in a container, e.g. {@code tam}, {@code ta},
     *            {@code Tamil}, {@code und}
     */
    public static Optional<String> normalise(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || UNKNOWN.contains(trimmed)) {
            return Optional.empty();
        }
        // "pt-BR" and "en_US" are region-qualified; the region is not a language and
        // splitting a library by it would put Brazilian and European Portuguese in
        // different piles.
        int separator = trimmed.indexOf('-') < 0 ? trimmed.indexOf('_') : trimmed.indexOf('-');
        if (separator > 0) {
            trimmed = trimmed.substring(0, separator);
        }
        String resolved = BY_SPELLING.get(trimmed);
        if (resolved != null) {
            return Optional.of(resolved);
        }
        return trimmed.length() <= 16 ? Optional.of(trimmed) : Optional.empty();
    }

    /**
     * Every distinct language in a probed audio track list, in the order the tracks
     * appear — so the first is the one a player picks by default, which is as close to
     * a title's "own" language as a container can say.
     *
     * @param audioTracks {@code index:codec:language:title} entries joined by {@code ;},
     *                    as {@link com.example.kido.media.catalog.MediaInfo} stores them
     */
    public static Set<String> fromAudioTracks(String audioTracks) {
        if (audioTracks == null || audioTracks.isBlank()) {
            return Set.of();
        }
        Set<String> languages = new LinkedHashSet<>();
        for (String entry : audioTracks.split(";")) {
            String[] parts = entry.split(":", -1);
            if (parts.length >= 3) {
                normalise(parts[2]).ifPresent(languages::add);
            }
        }
        return languages;
    }

    /** The English name for a canonical code, for a client that shows a chip. */
    public static String displayName(String code) {
        if (code == null || code.isBlank()) {
            return "Unknown";
        }
        String name = Locale.of(code).getDisplayLanguage(Locale.ENGLISH);
        return name.isEmpty() ? code : name;
    }

    /** Canonical codes for a caller-supplied list, e.g. a search filter. */
    public static Set<String> normaliseAll(Iterable<String> raw) {
        Set<String> codes = new LinkedHashSet<>();
        for (String value : raw) {
            normalise(value).ifPresent(codes::add);
        }
        return codes;
    }

    /** Exposed for the admin backfill's report, which names what it could not resolve. */
    public static boolean isKnown(String code) {
        return code != null && Arrays.asList(Locale.getISOLanguages()).contains(code);
    }
}
