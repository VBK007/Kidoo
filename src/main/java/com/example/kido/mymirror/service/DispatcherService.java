package com.example.kido.mymirror.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.MirrorAccessProperties;
import com.example.kido.mymirror.repo.DispatcherRepository;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves the backend base URL the rest of the mirror flow calls.
 *
 * <p>Discovery works like the app's: a mirror's {@code /check.php} hands back a base64 {@code
 * token_hash} that decodes to the content URL the app loads, e.g.
 * {@code https://net52.cc/mobile/home?app=1}. The base every other endpoint hangs off is the
 * <em>origin</em> of that URL ({@code https://net52.cc}) — not the whole string, and not one of the
 * {@code u[]} entries, which are video-CDN hosts, not API hosts.
 *
 * <p>The result is cached briefly: a mirror that answered a few minutes ago is almost certainly still
 * good, and re-walking the pool on every request would be slow and rude to the upstream.
 */
@Slf4j
@Service
public class DispatcherService {
    private static final long CACHE_MILLIS = 5 * 60 * 1000;

    private final DispatcherRepository repo;
    private final MirrorAccessProperties access;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private volatile String cachedUrl;
    private volatile long cachedUntil;

    /** Discovery only, with redirect-following off (the historical behaviour). */
    public DispatcherService(DispatcherRepository repo) {
        this(repo, null);
    }

    @Autowired
    public DispatcherService(DispatcherRepository repo, @Nullable MirrorAccessProperties access) {
        this.repo = repo;
        this.access = access;
    }

    /** The backend origin, without a trailing slash (e.g. {@code https://net52.cc}). 502 when discovery fails. */
    public String resolveActiveBaseUrl() {
        String url = cachedUrl;
        if (url != null && System.currentTimeMillis() < cachedUntil) return url;
        synchronized (this) {
            if (cachedUrl != null && System.currentTimeMillis() < cachedUntil) return cachedUrl;
            String resolved = resolveFromDispatcher();
            cachedUrl = resolved;
            cachedUntil = System.currentTimeMillis() + CACHE_MILLIS;
            return resolved;
        }
    }

    private String resolveFromDispatcher() {
        String body = repo.getDispatcherResponse();
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "No mirror is reachable right now");
        }
        String tokenHash;
        try {
            JsonNode node = mapper.readTree(body);
            tokenHash = node.path("token_hash").asString(null);
        } catch (RuntimeException e) {
            log.warn("Dispatcher response unreadable: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The mirror dispatcher did not answer properly");
        }
        String contentUrl = decode(tokenHash);
        String base = originOf(contentUrl);
        if (base == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The mirror dispatcher did not hand out a backend URL");
        }
        // The decoded origin can be stale: the backend 301s (e.g. net52.cc → net77.cc) to the host that
        // actually serves content behind Cloudflare. When asked to, follow it and hang the flow off the
        // landed origin instead of the stale one.
        if (access != null && access.isFollowRedirects()) {
            String landed = landedOrigin(contentUrl);
            if (landed != null && !landed.equals(base)) {
                log.info("Backend redirected {} -> {}", base, landed);
                base = landed;
            }
        }
        log.debug("Backend base resolved to {}", base);
        return base;
    }

    /** The decoded content URL, or {@code null} if the token is missing or not valid base64. */
    private static String decode(String encodedTokenHash) {
        if (encodedTokenHash == null || encodedTokenHash.isBlank()) return null;
        try {
            return new String(Base64.getDecoder().decode(encodedTokenHash.trim()), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The {@code scheme://host[:port]} of a URL string, or {@code null} if unusable. */
    private static String originOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) return null;
            String origin = uri.getScheme() + "://" + uri.getHost();
            return uri.getPort() == -1 ? origin : origin + ":" + uri.getPort();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Follows the content URL's redirects and returns the origin it lands on. Even when the landing is
     * Cloudflare's challenge (a 403), the response still comes from the rotated host, so its origin is
     * what the rest of the flow should target. Returns {@code null} on any failure, so discovery falls
     * back to the decoded origin rather than breaking.
     */
    @Nullable
    private String landedOrigin(String contentUrl) {
        if (contentUrl == null) return null;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(contentUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", userAgent())
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return originOf(response.uri().toString());
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Could not follow backend redirect, using the decoded origin: {}", e.getMessage());
            return null;
        }
    }

    private String userAgent() {
        String ua = access == null ? null : access.getBrowserUserAgent();
        return ua == null || ua.isBlank() ? ClientHeaderSupport.DEFAULT_USER_AGENT : ua.trim();
    }
}
