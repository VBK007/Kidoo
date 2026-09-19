package com.example.kido.media.music;

import java.util.regex.Pattern;

/**
 * Strips download-site branding that gets baked straight into ID3 tags — title, artist,
 * album and composer alike — by whichever site a track came from (e.g. {@code "Aathi -
 * Masstamilan.In"}, {@code "[Isaimini.Audio]"}, {@code "MassTamilan.com"} used as the
 * genre tag outright).
 *
 * <p>Originally lived only in {@link TrackArtworkService} as a narrower suffix pattern
 * for building iTunes search queries; broadened and centralised here once it became
 * clear the same junk sits in the persisted title/artist/album/composer fields
 * themselves, not just in queries built from them — a Spotify-style home screen showing
 * {@code "Aadiyil Kaathadicha [Masstamilan.in]"} as a track title would be worse than
 * showing nothing.
 */
public final class SiteWatermark {

    private SiteWatermark() {}

    /** A bracketed or bare {@code name.tld}, or one of the sites' bare names without a tld. */
    private static final Pattern SITE_TOKEN = Pattern.compile(
            "\\[?\\s*[\\w-]+\\.(com|in|net|org|io|dev|fm|audio|info|so|co|fun|me|app)\\s*\\]?"
                    + "|\\b(masstamilan|isaimini|myisaimini|tamilwap|audiotamil|starmusiq"
                    + "|kuttyweb|tamilanda|wapking|tnhits|tnmachi|mobilebgmringtones)\\w*\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EDGE_JUNK = Pattern.compile("^[\\s\\-:|,]+|[\\s\\-:|,]+$");

    /** @return the value with any site branding removed, or null if nothing usable remains */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = SITE_TOKEN.matcher(value).replaceAll("");
        cleaned = cleaned.replaceAll("\\s{2,}", " ");
        cleaned = EDGE_JUNK.matcher(cleaned).replaceAll("").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
