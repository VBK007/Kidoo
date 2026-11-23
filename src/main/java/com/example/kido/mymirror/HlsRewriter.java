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

    /**
     * Whether a master playlist carries at least one variant the player can actually stream — a
     * bare variant URL (the line after an {@code #EXT-X-STREAM-INF}) that resolves to a real host.
     *
     * <p>The mirror intermittently answers with a placeholder master whose only variant has an empty
     * authority ({@code https:///files/<id>/720p/720p.m3u8}) — a form seen while its CDN node mapping
     * for the title is still warming. Such a master is unplayable: the empty host carries no segments
     * and cannot be resolved (the real freecdn node and its content id are not derivable from it). The
     * caller uses this to re-request a master until a resolved one comes back, rather than handing the
     * player a master it can only fail on.
     */
    public static boolean hasPlayableVariant(String master, URI masterUrl) {
        if (master == null || master.isEmpty()) {
            return false;
        }
        for (String line : master.split("\r?\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue; // tags and blanks are not variant URLs
            }
            try {
                String host = masterUrl.resolve(trimmed).getHost();
                if (host != null && !host.isEmpty()) {
                    return true;
                }
            } catch (IllegalArgumentException ex) {
                // an unparseable variant line is not a playable one; keep looking
            }
        }
        return false;
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
