package com.example.kido.mymirror;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;

import com.example.kido.common.ApiException;

/** Building and checking the URLs this server fetches from on a user's behalf. */
public final class UpstreamUrl {

    private UpstreamUrl() {
    }

    /**
     * {@code base + path}, with each key/value pair appended as an encoded query parameter.
     * Values come from app users (a search box, a title), so a space or {@code &} in one
     * must stay inside that parameter rather than splitting the query.
     */
    public static URI of(String baseUrl, String path, String... params) {
        if (params.length % 2 != 0) {
            throw new IllegalArgumentException("params must be key/value pairs");
        }
        StringBuilder url = new StringBuilder(baseUrl).append(path);
        for (int i = 0; i < params.length; i += 2) {
            url.append(i == 0 ? '?' : '&')
                    .append(encode(params[i])).append('=').append(encode(params[i + 1]));
        }
        return URI.create(url.toString());
    }

    /** A path segment, encoded so an id cannot walk to another path. */
    public static String segment(String value) {
        return encode(value).replace("+", "%20");
    }

    /**
     * A reference from an upstream response resolved against {@code baseUrl}, kept on the mirror's
     * own host. The reference is the mirror's own {@code sources[].file} from playlist.php — a path
     * like {@code /mobile/hls/<id>.m3u8?in=<play_token>&hd=off&lang=eng&hp=yes}; resolving it keeps
     * the query, and the signing token inside it, verbatim, which {@link #of} (which re-encodes
     * params) cannot. The fetch that follows carries the session cookie, so it must land on the
     * same origin as the mirror.
     */
    public static URI resolveOnMirror(String baseUrl, String reference) {
        if (reference == null || reference.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "mirror returned no stream source");
        }
        URI resolved;
        try {
            resolved = URI.create(baseUrl).resolve(reference.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "mirror stream source is not a valid URL");
        }
        if (!sameOrigin(resolved, baseUrl)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "mirror stream source points off the mirror");
        }
        return resolved;
    }

    /**
     * The URL, if it is http(s) on a host that resolves only to public addresses. The
     * subtitle endpoint fetches whatever URL it is handed, so without this a signed-in user
     * could make the server read its own actuator, the LAN, or a cloud metadata address.
     */
    public static URI requirePublicHttp(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException | NullPointerException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "url is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "url must be an http(s) URL");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!isPublic(address)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "url must point at a public host");
                }
            }
        } catch (UnknownHostException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "url host does not resolve");
        }
        return uri;
    }

    public static boolean sameOrigin(URI url, String baseUrl) {
        URI base = URI.create(baseUrl);
        String scheme = url.getScheme();
        return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && scheme.equalsIgnoreCase(base.getScheme())
                && url.getUserInfo() == null
                && sameHost(url, baseUrl)
                && effectivePort(url) == effectivePort(base);
    }

    private static int effectivePort(URI url) {
        if (url.getPort() >= 0) return url.getPort();
        return "https".equalsIgnoreCase(url.getScheme()) ? 443 : 80;
    }

    /** Whether two URLs name the same host, ignoring case. */
    public static boolean sameHost(URI a, String b) {
        try {
            String other = URI.create(b).getHost();
            return a.getHost() != null && a.getHost().equalsIgnoreCase(other);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** Whether an address is reachable only over the public internet (not loopback, LAN, link-local). */
    public static boolean isPublic(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 16 && (b[0] & 0xfe) == 0xfc) {
            return false; // IPv6 unique-local fc00::/7, which isSiteLocalAddress does not cover
        }
        if (b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64) {
            return false; // 100.64.0.0/10, carrier-grade NAT and Tailscale
        }
        return true;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
