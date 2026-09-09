package com.example.kido.media.matchfix;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Scores how well two titles match, for the percentages shown beside each candidate.
 *
 * <p>Two measures are combined because each fails on cases the other handles. Token
 * overlap alone calls "The Matrix" and "Matrix Reloaded" a strong match, since one
 * token set contains the other. Edit distance alone is thrown badly off by word order
 * and by the release junk that clings to these filenames, so "Inception 1080p BluRay
 * x264" scores poorly against "Inception". Together they behave sensibly on both.
 *
 * <p>The year is treated as evidence rather than as part of the title: a matching year
 * is a strong confirmation, and a year that differs by more than one is a strong signal
 * that two similarly-named films are being confused — which is exactly the mistake this
 * screen exists to correct.
 */
public final class TitleSimilarity {

    private TitleSimilarity() {}

    /** Release tags that say nothing about which film this is. */
    private static final Set<String> NOISE_TOKENS = Set.of(
            "480p", "576p", "720p", "1080p", "1440p", "2160p", "4k", "8k", "uhd", "hd",
            // "blu" covers "blu-ray" once separators become spaces. "ray" is left alone
            // on purpose: stripping it would damage titles that legitimately contain it.
            "bluray", "blu", "brrip", "bdrip", "webrip", "webdl", "web", "hdrip",
            "dvdrip", "dvdscr", "hdtv", "remux", "proper", "repack", "extended",
            "x264", "x265", "h264", "h265", "hevc", "avc", "xvid", "divx",
            "aac", "ac3", "eac3", "dts", "dd5", "truehd", "atmos", "10bit", "8bit",
            "hdr", "hdr10", "dv", "sdr", "multi", "dual", "audio", "subs", "sub",
            "unrated", "directors", "cut", "imax", "amzn", "nf", "dsnp", "hmax");

    private static final Set<String> LEADING_ARTICLES = Set.of("the", "a", "an");

    /**
     * @return 0–100, where 100 means the normalised titles are identical and the years
     *         agree (or neither side claims one)
     */
    public static int score(String left, Integer leftYear, String right, Integer rightYear) {
        String a = normalise(left);
        String b = normalise(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }

        Set<String> tokensA = tokens(a);
        Set<String> tokensB = tokens(b);

        double textual = 0.6 * jaccard(tokensA, tokensB) + 0.4 * editSimilarity(a, b);
        double adjusted = textual + yearAdjustment(leftYear, rightYear);

        return (int) Math.round(Math.max(0, Math.min(1, adjusted)) * 100);
    }

    /**
     * Year agreement as a bonus or penalty rather than a filter.
     *
     * <p>A filter would be wrong in both directions: plenty of correct candidates carry
     * no year at all, and a remake shares its title with the original but is a different
     * film. So a match nudges the score up, a clear mismatch pulls it down hard, and a
     * one-year gap is ignored because release years disagree across regions.
     */
    private static double yearAdjustment(Integer left, Integer right) {
        if (left == null || right == null) {
            return 0;
        }
        int gap = Math.abs(left - right);
        if (gap == 0) {
            return 0.12;
        }
        if (gap == 1) {
            return 0;
        }
        return -0.30;
    }

    /** Lower-cased, punctuation stripped, release noise removed, article moved off. */
    static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT)
                // Separators used as spaces in release names.
                .replaceAll("[._\\-]+", " ")
                // Bracketed groups are almost always tags, not title.
                .replaceAll("[\\[({][^\\])}]*[\\])}]", " ")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();

        StringBuilder kept = new StringBuilder();
        for (String token : lower.split(" ")) {
            if (token.isEmpty() || NOISE_TOKENS.contains(token)) {
                continue;
            }
            // A bare four-digit year is scored separately, not as a title word.
            if (token.matches("(19|20)\\d{2}")) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append(' ');
            }
            kept.append(token);
        }

        String cleaned = kept.toString().trim();
        int space = cleaned.indexOf(' ');
        if (space > 0 && LEADING_ARTICLES.contains(cleaned.substring(0, space))) {
            return cleaned.substring(space + 1).trim();
        }
        return cleaned;
    }

    private static Set<String> tokens(String normalised) {
        Set<String> out = new LinkedHashSet<>();
        out.addAll(Arrays.asList(normalised.split(" ")));
        out.remove("");
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        long shared = a.stream().filter(b::contains).count();
        int union = a.size() + b.size() - (int) shared;
        return union == 0 ? 0 : (double) shared / union;
    }

    /** Levenshtein distance folded into a 0–1 similarity. */
    private static double editSimilarity(String a, String b) {
        int distance = levenshtein(a, b);
        int longest = Math.max(a.length(), b.length());
        return longest == 0 ? 1 : 1.0 - ((double) distance / longest);
    }

    /**
     * Two-row Levenshtein. Titles are short, so the quadratic cost is irrelevant, but
     * the full matrix is not worth allocating either.
     */
    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
