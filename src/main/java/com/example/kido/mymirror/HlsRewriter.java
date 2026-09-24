package com.example.kido.mymirror;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes every URI in an HLS playlist absolute against the upstream playlist's own URL.
 *
 * <p>The app's player loads the playlist from this server, so it resolves a relative
 * variant or segment path against this server too, and asks us for files we do not have.
 * Absolute URIs send it straight to the mirror instead.
 */
public final class HlsRewriter {

    public static final String MIME_TYPE = "application/vnd.apple.mpegurl";

    // URI="..." inside tags such as #EXT-X-MEDIA, #EXT-X-I-FRAME-STREAM-INF and #EXT-X-KEY.
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]*)\"");

    private HlsRewriter() {
    }

    public static String absolutize(String playlist, URI playlistUrl) {
        if (playlist == null || playlist.isEmpty()) {
            return playlist;
        }
        StringBuilder out = new StringBuilder(playlist.length() + 256);
        for (String line : playlist.split("\r?\n", -1)) {
            if (line.isBlank()) {
                out.append(line);
            } else if (line.startsWith("#")) {
                Matcher m = URI_ATTRIBUTE.matcher(line);
                StringBuilder tag = new StringBuilder();
                while (m.find()) {
                    m.appendReplacement(tag, Matcher.quoteReplacement("URI=\"" + resolve(playlistUrl, m.group(1)) + "\""));
                }
                m.appendTail(tag);
                out.append(tag);
            } else {
                out.append(resolve(playlistUrl, line.trim()));
            }
            out.append('\n');
        }
        out.setLength(out.length() - 1); // the split kept the input's final line ending as an empty last line
        return out.toString();
    }

    private static String resolve(URI base, String reference) {
        try {
            return base.resolve(reference).toString();
        } catch (IllegalArgumentException ex) {
            return reference; // leave anything unparseable exactly as the mirror sent it
        }
    }
}
