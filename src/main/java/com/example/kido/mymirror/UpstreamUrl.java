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
