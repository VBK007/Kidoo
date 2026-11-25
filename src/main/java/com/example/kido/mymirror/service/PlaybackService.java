package com.example.kido.mymirror.service;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.HlsRewriter;
import com.example.kido.mymirror.MirrorCloudflare;
import com.example.kido.mymirror.MirrorOtt;
import com.example.kido.mymirror.MirrorSessionProvider;
import com.example.kido.mymirror.MirrorRequestContext;
import com.example.kido.mymirror.MirrorRequestLimit;
import com.example.kido.mymirror.MirrorStreamSigner;
import com.example.kido.mymirror.UpstreamUrl;
import com.example.kido.mymirror.manager.TokenManager;
import com.example.kido.mymirror.repo.PlaybackRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service("mirrorPlaybackService")
public class PlaybackService {
    private static final Logger log = LoggerFactory.getLogger(PlaybackService.class);
    // Keep a short success cache and share only requests with the same upstream context.
    private static final long MASTER_CACHE_TTL_MS = 150_000;
    private static final int MASTER_CACHE_MAX = 256;
    private final ConcurrentHashMap<MasterKey, CachedMaster> masterCache = new ConcurrentHashMap<>();

    private record MasterKey(String origin, String id, String catalogue, String sessionIdentity, String callerScope, long generation) {}

    private record CachedMaster(long expiresAt, CompletableFuture<String> future) {}

    private final PlaybackRepository repo;
    private final DispatcherService dispatcherService;
    private final TokenManager tokenManager;
    private final MirrorStreamSigner signer;
    private final MirrorRequestLimit requestLimit;
    private final MirrorSessionProvider sessions;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public PlaybackService(PlaybackRepository repo, DispatcherService dispatcherService,
                           TokenManager tokenManager, MirrorStreamSigner signer, MirrorRequestLimit requestLimit, MirrorSessionProvider sessions) {
        this.repo = repo;
        this.dispatcherService = dispatcherService;
        this.tokenManager = tokenManager;
        this.signer = signer;
        this.requestLimit = requestLimit;
        this.sessions = sessions;
    }

    // The live p.php answers the same with any hash, including none, and no page hands one out
    // any more, so a missing hash must not fail the heartbeat.
    public String heartbeat(String cookie) {
        String baseUrl = dispatcherService.resolveActiveBaseUrl();
        return repo.heartbeat(baseUrl, tokenManager.tokensForOrigin(baseUrl, cookie).hash(), cookie);
    }

    public String altVideo(String cookie) {
        return repo.altVideo(dispatcherService.resolveActiveBaseUrl(), tokenManager.getHash(cookie), cookie);
    }

    public String streamtape(String cookie) {
        return repo.streamtape(dispatcherService.resolveActiveBaseUrl(), cookie);
    }

    public String playlist(String id, String title, String cookie) {
        // playbackT (not getT): the stream is authorized by the signed play_token playlist.php mints,
        // not by the per-play ad-gate, so a play must not 412 on a missing ad click. See hlsMaster.
        return repo.fetchPlaylist(dispatcherService.resolveActiveBaseUrl(), id, title, tokenManager.playbackT(cookie), cookie);
    }

    /**
     * The master playlist, with every entry made absolute so the player fetches it from the mirror.
     *
     * <p>The master URL is not reconstructed from the id and session token: the mirror signs each
     * playback with a separate, short-TTL HLS {@code in=} play_token minted by playlist.php, and a
     * master requested with anything else (the session token included) is answered with a locked
     * placeholder that 403s on its segments. So this asks playlist.php, takes the signed
     * {@code sources[].file} it returns — a path that already carries the right play_token — and
     * fetches that. Prime vs Netflix is handled inside {@link PlaybackRepository#fetchPlaylist} by
     * the id's shape, so it need not be decided again here.
     */
    /** Only local reads. A miss must go through the foreground bulkhead. */
    public String cachedHlsMaster(String id, String cookie, String scope) {
        if (scope == null) return null;
        long generation = sessions.generation(cookie);
        requestLimit.checkCooldown();
        String origin = dispatcherService.cachedActiveBaseUrl();
        if (origin == null) return null;
        CachedMaster entry = masterCache.get(masterKey(origin, id, cookie, scope, generation));
        if (entry == null || System.currentTimeMillis() >= entry.expiresAt()
                || !entry.future().isDone() || entry.future().isCompletedExceptionally()) return null;
        String master = entry.future().getNow(null);
        sessions.ifCurrent(cookie, generation, () -> {});
        logCache("hit");
        return master;
    }

    private MasterKey masterKey(String origin, String id, String cookie, String scope, long generation) {
        String catalogue = MirrorOtt.isPrime(id, cookie) ? "pv" : MirrorOtt.isHotstar(id, cookie) ? "dp" : "nf";
        return new MasterKey(origin, id, catalogue, signer.sign(cookie), scope, generation);
    }

    public String hlsMaster(String id, String cookie) {
        return hlsMaster(id, cookie, "");
    }

    public String hlsMaster(String id, String cookie, String callerScope) {
        long generation = sessions.generation(cookie);
        requestLimit.checkCooldown();
        String baseUrl = dispatcherService.resolveActiveBaseUrl();
        if (callerScope == null) {
            logCache("bypass");
            String master = resolveMaster(baseUrl, id, cookie);
            sessions.ifCurrent(cookie, generation, () -> {});
            return master;
        }
        MasterKey key = masterKey(baseUrl, id, cookie, callerScope, generation);
        AtomicReference<CompletableFuture<String>> owned = new AtomicReference<>();
        CachedMaster entry = masterCache.compute(key, (ignored, existing) -> {
            long now = System.currentTimeMillis();
            boolean reusable = existing != null
                    && (!existing.future().isDone()                           // a resolution in flight
                        || (!existing.future().isCompletedExceptionally()     // or a still-fresh success
                            && now < existing.expiresAt()));
            if (reusable) {
                return existing;
            }
            CompletableFuture<String> fresh = new CompletableFuture<>();
            owned.set(fresh);
            return new CachedMaster(now + MASTER_CACHE_TTL_MS, fresh);
        });

        logCache(owned.get() == entry.future() ? "miss" : entry.future().isDone() ? "hit" : "joined");
        if (owned.get() == entry.future()) {
            // This call created the shared future, so it does the upstream work and completes it; every
            // other caller waiting on the same title and cookie gets this one result (success or failure).
            try {
                String master = resolveMaster(baseUrl, id, cookie);
                sessions.ifCurrent(cookie, generation, () -> owned.get().complete(master));
            } catch (RuntimeException | Error ex) {
                owned.get().completeExceptionally(ex);
                masterCache.remove(key, entry);
            }
            pruneMasterCache();
        }

        try {
            long remaining = MirrorRequestContext.deadlineWithin(Duration.ofSeconds(30)) - System.nanoTime();
            String master = entry.future().get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
            sessions.ifCurrent(cookie, generation, () -> {});
            return master;
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException failure) throw failure;
            if (ex.getCause() instanceof Error failure) throw failure;
            throw new IllegalStateException(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Playback start interrupted");
        } catch (TimeoutException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Playback start in progress");
        }
    }

    private void logCache(String outcome) {
        io.micrometer.core.instrument.Metrics.counter("mirror.master.cache", "outcome", outcome).increment();
        log.atInfo().addKeyValue("playbackAttemptId", MDC.get(MirrorRequestContext.ATTEMPT))
                .addKeyValue("cache", outcome).log("Mirror master lookup");
    }

    private void startup(Runnable request) {
        try {
            request.run();
        } catch (HttpStatusCodeException ex) {
            if (MirrorCloudflare.isChallenge(ex) || ex.getStatusCode().value() == 403) throw ex;
            log.debug("Optional mirror startup request returned status {}", ex.getStatusCode().value());
        } catch (RestClientException ex) {
            log.debug("Optional mirror startup request failed: {}", ex.getClass().getSimpleName());
        } catch (ApiException ex) {
            if (ex.getStatus() != HttpStatus.PRECONDITION_FAILED) throw ex;
            log.debug("Mirror startup request was deferred by an upstream precondition");
        }
    }

    // Drop done entries that are failed or past their TTL once the map grows past a modest cap. Entries
    // are otherwise only replaced on a fresh request for the same id, so without this a long run across
    // many distinct titles would accumulate dead futures. Never removes an in-flight resolution.
    private void pruneMasterCache() {
        if (masterCache.size() <= MASTER_CACHE_MAX) {
            return;
        }
        long now = System.currentTimeMillis();
        masterCache.values().removeIf(c -> c.future().isDone()
                && (c.future().isCompletedExceptionally() || now >= c.expiresAt()));
    }

    private String resolveMaster(String baseUrl, String id, String cookie) {
        String playbackToken = tokenManager.playbackT(cookie);
        startup(() -> repo.heartbeat(baseUrl, tokenManager.tokensForOrigin(baseUrl, cookie).hash(), cookie));
        startup(() -> repo.openTitle(baseUrl, id, playbackToken, cookie));
        String playlist = repo.fetchPlaylist(baseUrl, id, "", playbackToken, cookie);
        URI masterUrl = UpstreamUrl.resolveOnMirror(baseUrl, masterSource(playlist));
        String master = repo.fetchHlsMaster(baseUrl, masterUrl, cookie);
        if (isRateLimitPenalty(master)) {
            sessions.observe(cookie, MirrorSessionProvider.State.THROTTLED);
            throw requestLimit.throttled(null);
        }
        if (!HlsRewriter.hasPlayableVariant(master, masterUrl)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The streaming source returned no usable rendition");
        }

        // The CDN gates each variant playlist on the mirror Referer; route them through our proxy,
        // pinned to the host live NOW so a mid-stream mirror rotation cannot invalidate the Referer.
        return HlsRewriter.rewriteMaster(master, masterUrl, upstream -> variantProxyPath(upstream, baseUrl));
    }

    /** The signed {@code /playback/variant} path the master hands the player for one variant URL. */
    private String variantProxyPath(String upstreamUrl, String baseUrl) {
        String token = signer.encodeLink(baseUrl + "\n" + upstreamUrl);
        return "/playback/variant?u=" + token + "&s=" + signer.sign(token);
    }

    /**
     * A variant (media) playlist, fetched from the mirror's segment CDN on the player's behalf.
     *
     * <p>The {@code u}/{@code s} pair is a token this server itself signed in {@link #hlsMaster};
     * it carries the CDN URL to fetch and the mirror Referer the CDN requires. The signature and a
     * host check keep the proxy from being pointed at anything but the CDN (or the mirror). The
     * playlist's segments are public, so they are rewritten to absolute CDN URLs the player streams
     * directly — only the small playlist itself passes through this server.
     */
    public String variant(String u, String s) {
        String payload = signer.verify(u, s);
        if (payload == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "invalid stream token");
        }
        int split = payload.indexOf('\n');
        String referer = split < 0 ? "" : payload.substring(0, split);
        String target = split < 0 ? payload : payload.substring(split + 1);
        URI url = UpstreamUrl.requirePublicHttp(target);
        if (!isStreamHost(url, referer)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "stream host is not allowed");
        }
        return HlsRewriter.absolutize(repo.fetchCdnPlaylist(url, referer), url);
    }

    // The mirror's segment CDN is a rotating, numbered domain family, each fronted by node subdomains
    // like s13.freecdn2.top / s23.nm-cdn9.top, and both the member and the family name change over
    // time. It began as freecdn<N>.top (freecdn2.top, freecdn4.top, …) and the live app has since
    // been observed streaming from nm-cdn<N>.top (s23.nm-cdn9.top) — a straight rename of the same
    // segment CDN. Pinning a single domain meant that the moment the mirror handed back a master
    // pointing at a sibling node (or the renamed family) every variant 403'd with "stream host is not
    // allowed" and nothing played. Match the whole family — freecdn<N>.top OR nm-cdn<N>.top, apex or
    // any subdomain label depth — rather than one fixed member. This is a defence-in-depth guard
    // layered on top of the signature: the URL can only be one this server itself signed in hlsMaster
    // from a master the mirror returned, so the host check narrows an already-trusted URL rather than
    // admitting attacker input — but it stays scoped to the CDN family and the mirror, never arbitrary.
    private static final Pattern STREAM_CDN_HOST =
            Pattern.compile("(?:[a-z0-9-]+\\.)*(?:freecdn|nm-cdn)\\d+\\.top");

    /** The segment CDN family, or the mirror host itself — the only hosts the variant proxy may fetch. */
    private static boolean isStreamHost(URI url, String referer) {
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        return STREAM_CDN_HOST.matcher(host).matches() || UpstreamUrl.sameHost(url, referer);
    }

    /**
     * The signed master path out of a playlist.php body
     * ({@code [{"sources":[{"file":"/mobile/hls/<id>.m3u8?in=<play_token>&…","label":"Auto"},…]}]}).
     * The "Auto" source is the multi-variant master the player adapts over; the {@code q=720p}/
     * {@code q=480p} entries are single renditions, so Auto is preferred and the first usable
     * {@code file} is the fallback. A body that drifts to carrying {@code file} on the element
     * itself is still read.
     */
    private String masterSource(String playlistJson) {
        JsonNode root;
        try {
            root = mapper.readTree(playlistJson);
        } catch (RuntimeException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "mirror playlist was not valid JSON");
        }
        JsonNode entry = root.isArray() ? root.path(0) : root;
        String fallback = "";
        for (JsonNode source : entry.path("sources")) {
            String file = source.path("file").asString("").trim();
            if (file.isEmpty()) continue;
            if ("Auto".equalsIgnoreCase(source.path("label").asString(""))) return file;
            if (fallback.isEmpty()) fallback = file;
        }
        if (fallback.isEmpty()) fallback = entry.path("file").asString("").trim();
        if (fallback.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "mirror playlist carried no playable source");
        }
        return fallback;
    }

    // This signature identifies the penalty fixture previously observed by the project.
    private static final Pattern RATE_LIMIT_PENALTY = Pattern.compile(
            "/files/220884/[^\\s?]+[?][^\\s]*\\bin=unknown\\b");

    /** Whether a fetched master is the mirror's rate-limit penalty clip rather than the real title. */
    private static boolean isRateLimitPenalty(String masterBody) {
        return masterBody != null && RATE_LIMIT_PENALTY.matcher(masterBody).find();
    }

    public String subtitle(String subtitleUrl, String cookie) {
        URI url = UpstreamUrl.requirePublicHttp(subtitleUrl);
        return repo.fetchSubtitle(url, dispatcherService.resolveActiveBaseUrl(), cookie);
    }
}
