package com.example.kido.mymirror.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.MirrorAccessProperties;
import com.example.kido.mymirror.MirrorRequestLimit;
import com.example.kido.mymirror.MirrorRequestContext;
import com.example.kido.mymirror.MirrorCloudflare;
import org.springframework.web.client.HttpStatusCodeException;
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

    private final ReentrantLock discovery = new ReentrantLock();
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
    public String cachedActiveBaseUrl() {
        String url = cachedUrl;
        return url != null && System.currentTimeMillis() < cachedUntil ? url : null;
    }

    public String resolveActiveBaseUrl() {
        String url = cachedActiveBaseUrl();
        if (url != null) return url;
        return MirrorRequestContext.within(Duration.ofSeconds(12), this::discoverWithinDeadline);
    }

    private String discoverWithinDeadline() {
        long deadline = MirrorRequestContext.deadlineWithin(Duration.ofSeconds(12));
        try {
            if (!discovery.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                throw new MirrorRequestLimit.DeadlineExceeded();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Discovery interrupted");
        }
        try {
            if (cachedUrl != null && System.currentTimeMillis() < cachedUntil) return cachedUrl;
            String resolved = resolveFromDispatcher();
            cachedUrl = resolved;
            cachedUntil = System.currentTimeMillis() + CACHE_MILLIS;
            return resolved;
        } finally {
            discovery.unlock();
        }
    }

    /**
     * Drops the cached base so the next {@link #resolveActiveBaseUrl()} re-walks discovery. Used
     * after a transient upstream failure that may mean the backend rotated hosts (net52 → net77)
     * within the cache window, so the next call targets the host that actually answers now.
     */
    public void invalidate() {
        cachedUntil = 0;
    }

    /**
     * Compare-and-invalidate: drops the cached base <em>only if it is still {@code base}</em> — the exact
     * origin a caller just saw fail during playback. A plain {@link #invalidate()} would also wipe a base
     * that a concurrent request has <em>already</em> rediscovered and replaced, turning one host rotation
     * into a stampede of re-discoveries; pinning the drop to the host that actually failed means a late
     * failure from a host that is no longer current is a no-op, and concurrent failures on the same dead
     * host collapse (with the single-flight {@link #discovery} lock) to a single re-walk. Used by the
     * playback failover path; the home feed still calls {@link #invalidate()} unconditionally.
     */
    public void invalidateIfCurrent(@Nullable String base) {
        if (base != null && base.equals(cachedUrl)) {
            cachedUntil = 0;
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
            log.warn("Dispatcher response unreadable");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The mirror dispatcher did not answer properly");
        }
        String contentUrl = decode(tokenHash);
        String base = originOf(contentUrl);
        if (base == null || (access != null && !access.isApproved(URI.create(base)))) {
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

    @Nullable
    private String landedOrigin(String contentUrl) {
        try {
            URI location = repo.redirectLocation(URI.create(contentUrl));
            if (location == null) return null;
            if (!access.isApproved(location)) {
                throw new MirrorRequestLimit.AccessDenied();
            }
            return originOf(location.toString());
        } catch (HttpStatusCodeException ex) {
            if (MirrorCloudflare.isChallenge(ex)) throw new MirrorRequestLimit.AccessDenied();
            return null;
        } catch (MirrorRequestLimit.Throttled | MirrorRequestLimit.Busy | MirrorRequestLimit.AccessDenied
                 | MirrorRequestLimit.DeadlineExceeded ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Backend redirect probe failed: {}", ex.getClass().getSimpleName());
            return null;
        }
    }
}
