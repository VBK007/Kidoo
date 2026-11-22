package com.example.kido.mymirror.service;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.HlsRewriter;
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

    // The mirror intermittently answers playlist.php with a master whose only variant has an empty
    // host (https:///files/<id>/…), a transient state while its CDN node mapping for the title warms
    // up. The first requests for a cold title trigger that resolution and then it serves a resolved,
    // real-host master consistently (measured: a cold miss, then 20/20 resolved within seconds). So
    // re-request a fresh master a few times, with a short pause between tries, letting the request
    // itself ride out the warm-up rather than handing the player a 502 to retry. A warm title
    // resolves on the first try and never pauses; a title still cold after the whole span fails with
    // a clear "retry shortly" rather than emitting a broken master.
    private static final int MASTER_FETCH_ATTEMPTS = 6;
    private static final long MASTER_RETRY_BACKOFF_MS = 400;

    // Single-flight + short success cache for resolved masters. Resolving a master is the heaviest
    // upstream call on the playback path — it hits playlist.php (which mints the play_token) and then
    // fetches the master — and doing it repeatedly on one title in a short window is exactly what trips
    // net52's per-client cap on the shared upstream session, after which every title 502/503s. Two
    // things pile those redundant resolutions on: the player retrying a failed manifest load, and
    // several viewers opening the same title at once. Both collapse here. A resolution in flight for an
    // id is shared by every caller that arrives while it runs (single-flight), and a resolved master is
    // reused for a few seconds after (cache) so a re-open or a manifest refresh does not re-hit
    // upstream. The window is deliberately far shorter than the play_token's lifetime, so a served
    // master always carries a token the CDN still honours. Because the upstream session is shared, the
    // master a resolution yields does not depend on which caller triggered it, so the id alone keys the
    // cache. Failures are never cached: a transient 502/503 must be retryable at once (and the client no
    // longer storms it), so a failed resolution is evicted immediately and the next caller retries fresh.
    private static final long MASTER_CACHE_TTL_MS = 8_000;
    private static final int MASTER_CACHE_MAX = 256;
    private final ConcurrentHashMap<String, CachedMaster> masterCache = new ConcurrentHashMap<>();

    private record CachedMaster(long expiresAt, CompletableFuture<String> future) {}

    private final PlaybackRepository repo;
    private final DispatcherService dispatcherService;
    private final TokenManager tokenManager;
    private final MirrorStreamSigner signer;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public PlaybackService(PlaybackRepository repo, DispatcherService dispatcherService,
                           TokenManager tokenManager, MirrorStreamSigner signer) {
        this.repo = repo;
        this.dispatcherService = dispatcherService;
        this.tokenManager = tokenManager;
        this.signer = signer;
    }

    // The live p.php answers the same with any hash, including none, and no page hands one out
    // any more, so a missing hash must not fail the heartbeat.
    public String heartbeat(String cookie) {
        return repo.heartbeat(dispatcherService.resolveActiveBaseUrl(), tokenManager.tokensFor(cookie).hash(), cookie);
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
    public String hlsMaster(String id, String cookie) {
        // Coalesce concurrent/rapid resolutions of the same id onto one upstream call, and reuse a
        // fresh success for a few seconds. See MASTER_CACHE_TTL_MS. The compute runs under the map's
        // bin lock, so it only picks (or creates) the shared future — the actual upstream fetch runs
        // outside the lock, below, driven by whichever call created the future.
        AtomicReference<CompletableFuture<String>> owned = new AtomicReference<>();
        CachedMaster entry = masterCache.compute(id, (key, existing) -> {
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

        if (owned.get() == entry.future()) {
            // This call created the shared future, so it does the upstream work and completes it; every
            // other caller waiting on the same id gets this one result (success or failure).
            try {
                owned.get().complete(resolveMaster(id, cookie));
            } catch (RuntimeException ex) {
                masterCache.remove(id, entry); // never pin a failure; the next play retries fresh
                owned.get().completeExceptionally(ex);
            }
            pruneMasterCache();
        }

        try {
            return entry.future().join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ApiException api) throw api;
            if (cause instanceof RuntimeException re) throw re;
            throw ex;
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

    private String resolveMaster(String id, String cookie) {
        String baseUrl = dispatcherService.resolveActiveBaseUrl();
        // playlist.php's t= param is the title, used upstream for display/analytics, not the
        // signature, so this route (which is handed only the id) leaves it blank.
        //
        // playbackT, not getT: getT runs the per-play ad-gate verify (verify2.php) and 412s until a
        // human clicks a fresh ad on the device. But the stream is authorized by the signed in=
        // play_token playlist.php mints inside sources[].file, not by that gate — the master and its
        // variants fetch and play on the session cookie alone (confirmed live while the gate reported
        // "Waiting for your ads click."). The tm= value is only playlist.php's display/analytics
        // param, which the mirror accepts empty, so playback reads t from the cookie and never gates
        // a play on an ad click. See TokenManager#playbackT.
        String playbackT = tokenManager.playbackT(cookie);

        // The real NetMirror app POSTs /mobile/p.php (presence/keep-alive) immediately before it
        // fetches playlist.php and the HLS master — captured live as the leading call of every play.
        // Mirror it here, once per resolution (outside the retry loop, so it does not add to the
        // per-client request pressure that trips net52's cap). Best-effort: a failed or reload
        // heartbeat must never fail the play, which is authorized by the signed play_token, not p.php.
        try {
            repo.heartbeat(baseUrl, tokenManager.tokensFor(cookie).hash(), cookie);
        } catch (RuntimeException ex) {
            log.debug("play presence heartbeat failed (ignored): {}", ex.toString());
        }

        // The mirror sometimes returns a placeholder master whose only variant has an empty host —
        // unplayable, and with no way to derive the real CDN node from it. It is a transient warm-up
        // state, so re-request a fresh master until one resolves to a real host (see
        // MASTER_FETCH_ATTEMPTS); keep the first usable one.
        URI masterUrl = null;
        String master = null;
        for (int attempt = 1; attempt <= MASTER_FETCH_ATTEMPTS; attempt++) {
            String playlist = repo.fetchPlaylist(baseUrl, id, "", playbackT, cookie);
            URI candidateUrl = UpstreamUrl.resolveOnMirror(baseUrl, masterSource(playlist));
            String candidate = repo.fetchHlsMaster(baseUrl, candidateUrl, cookie);
            // Diagnostic only (no secrets): the master URL's query param NAMES (keys, never values —
            // the in= play_token is a secret) reveal whether playlist.php handed us hd/lang/hp like it
            // does the real app; the first variant host (a public CDN host, or <empty>) reveals whether
            // net52 resolved a CDN node for this session. This is how we tell a cookie/session gap from
            // a param gap when the master comes back empty-host. Remove once the empty-host cause lands.
            log.info("resolveMaster id={} attempt={}/{} masterParams=[{}] variantHost={} playable={}",
                    id, attempt, MASTER_FETCH_ATTEMPTS, paramNames(candidateUrl),
                    firstVariantHost(candidate, candidateUrl),
                    HlsRewriter.hasPlayableVariant(candidate, candidateUrl));
            // A rate-limit penalty master is itself "playable" (a real CDN host, a valid m3u8), so it
            // must be caught before the playable-variant check or the player streams the "STOP ABUSE"
            // clip as if it were the title. Do not retry: this is not a warm-up miss, and another
            // attempt only adds to the load that tripped the cap without clearing it.
            if (isRateLimitPenalty(candidate)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The mirror is rate-limited right now (upstream reported too many requests); "
                                + "wait a few minutes and try again");
            }
            if (HlsRewriter.hasPlayableVariant(candidate, candidateUrl)) {
                masterUrl = candidateUrl;
                master = candidate;
                break;
            }
            if (attempt < MASTER_FETCH_ATTEMPTS) {
                try {
                    Thread.sleep(MASTER_RETRY_BACKOFF_MS); // give the mirror's warm-up a beat to land
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (master == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The mirror has not resolved a CDN source for this title yet; retry shortly");
        }

        // The CDN gates each variant playlist on the mirror Referer; route them through our proxy,
        // pinned to the host live NOW so a mid-stream mirror rotation cannot invalidate the Referer.
        return HlsRewriter.rewriteMaster(master, masterUrl, upstream -> variantProxyPath(upstream, baseUrl));
    }

    /** The signed {@code /playback/variant} path the master hands the player for one variant URL. */
    private String variantProxyPath(String upstreamUrl, String baseUrl) {
        String token = signer.encode(baseUrl + "\n" + upstreamUrl);
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

    // The mirror does not answer a rate-limited session with an HTTP error. When the shared upstream
    // session trips net52.cc's per-client cap ("Too Many Requests in Short Period of Time") it swaps
    // the video rendition for a fixed penalty clip and still emits a 200 master, so the player would
    // otherwise stream a "STOP ABUSE — visit Netmirror.app" notice in place of the title. A genuine
    // play carries the signed in=<play_token> playlist.php minted; the penalty master instead marks
    // its renditions in=unknown — the mirror minted no real token for the play. That no-token marker
    // is the discriminator (derived from a live capture:
    // .../files/220884/720p/720p.m3u8?in=unknown::ni::m); it is far more stable than the fixed penalty
    // asset id, and a real play never carries it. If upstream ever emits a penalty under a different
    // marker this will miss it, which fails safe (the player sees the clip, as before) rather than
    // blocking a real title.
    private static final Pattern RATE_LIMIT_PENALTY = Pattern.compile("[?&]in=unknown\\b");

    /** Whether a fetched master is the mirror's rate-limit penalty clip rather than the real title. */
    private static boolean isRateLimitPenalty(String masterBody) {
        return masterBody != null && RATE_LIMIT_PENALTY.matcher(masterBody).find();
    }

    /** The query param NAMES of a URL (keys only — values, including the in= play_token, are secrets). */
    private static String paramNames(URI url) {
        String q = url == null ? null : url.getRawQuery();
        if (q == null || q.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (sb.length() > 0) sb.append(',');
            sb.append(eq >= 0 ? pair.substring(0, eq) : pair);
        }
        return sb.toString();
    }

    /** The host of a master's first variant URL (a public CDN host), or a marker if it has none. */
    private static String firstVariantHost(String master, URI masterUrl) {
        if (master == null) {
            return "<null-body>";
        }
        for (String line : master.split("\r?\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            try {
                String host = masterUrl.resolve(trimmed).getHost();
                return (host == null || host.isEmpty()) ? "<empty>" : host;
            } catch (IllegalArgumentException ex) {
                return "<unparseable>";
            }
        }
        return "<no-variant>";
    }

    public String subtitle(String subtitleUrl, String cookie) {
        URI url = UpstreamUrl.requirePublicHttp(subtitleUrl);
        return repo.fetchSubtitle(url, dispatcherService.resolveActiveBaseUrl(), cookie);
    }
}
