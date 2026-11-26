package com.example.kido.mymirror;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites an HLS playlist so the app's player can fetch it from the right place.
 *
 * <p>Two jobs. {@link #absolutize} makes every relative URI absolute against the upstream
 * playlist's own URL — the player loads the playlist from this server, so a relative path would
 * resolve back to this server, which does not have the file. It is used on a variant (media)
 * playlist, whose segments live on the CDN and are public, so the player streams them directly.
 *
 * <p>{@link #rewriteMaster} handles the master: it sends each variant playlist through this
 * server's {@code /playback/variant} proxy (the CDN gates variant playlists on a mirror Referer the
 * player cannot set), and it drops a rendition the mirror emits with no host — a dead audio track
 * (its audio is muxed into the video segments) that a strict player refuses the whole master over.
 */
public final class HlsRewriter {

    public static final String MIME_TYPE = "application/vnd.apple.mpegurl";

    // URI="..." inside tags such as #EXT-X-MEDIA, #EXT-X-I-FRAME-STREAM-INF and #EXT-X-KEY.
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]*)\"");

    private HlsRewriter() {
    }

    /**
     * Rewrites a master playlist for the player. Each variant/rendition URL that has a real host is
     * sent through {@code variantProxy} (so the proxy can add the Referer the CDN wants); a
     * rendition whose URI resolves to no host is dropped, and any {@code AUDIO}/{@code SUBTITLES}
     * group it defined is stripped from the {@code #EXT-X-STREAM-INF} lines so the player falls back
     * to the stream's own muxed track rather than a group with no playable member.
     */
    public static String rewriteMaster(String master, URI masterUrl, UnaryOperator<String> variantProxy) {
        if (master == null || master.isEmpty()) {
            return master;
        }
        String[] lines = master.split("\r?\n", -1);

        // First pass: find the rendition groups that have to be dropped (no host on their URI).
        Set<String> droppedGroups = new HashSet<>();
        for (String line : lines) {
            if (line.startsWith("#EXT-X-MEDIA:")) {
                String uri = attribute(line, "URI");
                if (uri != null && noHost(masterUrl, uri)) {
                    String group = attribute(line, "GROUP-ID");
                    if (group != null) {
                        droppedGroups.add(group);
                    }
                }
            }
        }

        StringBuilder out = new StringBuilder(master.length() + 256);
        for (String line : lines) {
            if (line.isBlank()) {
                out.append(line);
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                String uri = attribute(line, "URI");
                if (uri != null && noHost(masterUrl, uri)) {
                    continue; // drop the dead rendition entirely
                }
                out.append(proxyUriAttribute(line, masterUrl, variantProxy));
            } else if (line.startsWith("#EXT-X-STREAM-INF")) {
                out.append(stripGroups(line, droppedGroups));
            } else if (line.startsWith("#")) {
                out.append(proxyUriAttribute(line, masterUrl, variantProxy)); // e.g. I-FRAME-STREAM-INF
            } else {
                out.append(variantProxy.apply(masterUrl.resolve(line.trim()).toString()));
            }
            out.append('\n');
        }
        out.setLength(out.length() - 1); // the split kept the input's final line ending as an empty last line
        return out.toString();
    }

    public enum PlaylistKind { MASTER, MEDIA, MALFORMED, AMBIGUOUS }

    private static final Pattern BANDWIDTH = Pattern.compile("(?:^|,)BANDWIDTH=([1-9][0-9]*)(?:,|$)");

    /** Parse the playlist type and the URI declarations used by the playback master endpoint. */
    public static PlaylistKind playlistKind(String body, URI base) {
        if (body == null) return PlaylistKind.MALFORMED;
        String[] lines = body.split("\r?\n", -1);
        if (!"#EXTM3U".equals(lines[0])) return PlaylistKind.MALFORMED;
        boolean master = false;
        boolean media = false;
        boolean variant = false;
        boolean segment = false;
        boolean needsUri = false;
        boolean streamInf = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || (line.startsWith("#") && !line.startsWith("#EXT"))) continue;
            if (needsUri) {
                if (line.startsWith("#") || !httpUri(base, line)) return PlaylistKind.MALFORMED;
                if (streamInf) variant = true;
                else segment = true;
                needsUri = false;
                continue;
            }
            if (!line.startsWith("#") || line.equals("#EXTM3U")) return PlaylistKind.MALFORMED;
            if (line.startsWith("#EXT-X-STREAM-INF:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) {
                master = true;
                String attributes = line.substring(line.indexOf(':') + 1);
                if (!BANDWIDTH.matcher(attributes).find()) return PlaylistKind.MALFORMED;
                streamInf = line.startsWith("#EXT-X-STREAM-INF:");
                needsUri = streamInf;
                if (!streamInf) {
                    if (!httpUri(base, attribute(line, "URI"))) return PlaylistKind.MALFORMED;
                    variant = true;
                }
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                master = true;
                String uri = attribute(line, "URI");
                // Preserve rewriteMaster's handling of the provider's known hostless alternate rendition.
                if (uri != null && !uri.startsWith("https:///") && !httpUri(base, uri)) return PlaylistKind.MALFORMED;
            } else if (line.startsWith("#EXTINF:") || line.startsWith("#EXT-X-TARGETDURATION:")
                    || line.startsWith("#EXT-X-MEDIA-SEQUENCE:") || line.equals("#EXT-X-ENDLIST")) {
                media = true;
                streamInf = false;
                needsUri = line.startsWith("#EXTINF:");
            }
        }
        if (needsUri) return PlaylistKind.MALFORMED;
        if (master && media) return PlaylistKind.AMBIGUOUS;
        if (variant) return PlaylistKind.MASTER;
        if (segment) return PlaylistKind.MEDIA;
        return PlaylistKind.MALFORMED;
    }

    private static boolean httpUri(URI base, String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = base.resolve(value);
            return uri.getHost() != null && uri.getRawUserInfo() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** An attribute's value from an HLS tag line, quoted or bare, or {@code null} if absent. */
    private static String attribute(String line, String name) {
        Matcher m = Pattern.compile("(?:^|[,:])" + name + "=(?:\"([^\"]*)\"|([^,]*))").matcher(line);
        if (m.find()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }

    /** Whether a reference resolves to no host against the base (an absolute URI with empty authority). */
    private static boolean noHost(URI base, String reference) {
        try {
            URI resolved = base.resolve(reference.trim());
            return resolved.getHost() == null || resolved.getHost().isEmpty();
        } catch (IllegalArgumentException ex) {
            return true; // unparseable counts as dead
        }
    }

    /** Removes the given quoted {@code AUDIO}/{@code SUBTITLES} group references from a STREAM-INF line. */
    private static String stripGroups(String line, Set<String> droppedGroups) {
        String result = line;
        for (String name : new String[] {"AUDIO", "SUBTITLES"}) {
            String value = attribute(result, name);
            if (value != null && droppedGroups.contains(value)) {
                String token = name + "=\"" + value + "\"";
                result = result.replace("," + token, "").replace(token + ",", "").replace(token, "");
            }
        }
        return result;
    }

    /** Rewrites a {@code URI="..."} attribute, resolved absolute against the base, through the proxy. */
    private static String proxyUriAttribute(String line, URI base, UnaryOperator<String> proxy) {
        Matcher m = URI_ATTRIBUTE.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String absolute = base.resolve(m.group(1)).toString();
            m.appendReplacement(sb, Matcher.quoteReplacement("URI=\"" + proxy.apply(absolute) + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
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
