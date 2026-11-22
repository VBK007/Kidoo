package com.example.kido.mymirror.manager;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.MirrorCloudflare;
import com.example.kido.mymirror.MirrorSessionProvider;
import com.example.kido.mymirror.model.ClientHeaders;
import com.example.kido.mymirror.repo.AdGateRepository;
import com.example.kido.mymirror.service.DispatcherService;

import lombok.extern.slf4j.Slf4j;

/**
 * The {@code t} and {@code hash} tokens for a user session: {@code t} from the verified
 * {@code t_hash_t} cookie, {@code hash} from the home page when the mirror still serves it. The site ties them to the cookie (and device) that cleared its ad-gate, so a
 * pair fetched for one user is no good to another.
 */
@Slf4j
@Component
public class TokenManager {
    private static final long LIFETIME_SECONDS = 180;
    // How the live mirror signals a cleared gate. Uncleared it answers
    // {"c":"n","statusup":"Waiting for your ads click."}; a real ad click flips both fields.
    private static final String AD_GATE_CLEARED_STATUS = "\"statusup\":\"OK\"";
    private static final String AD_GATE_CLEARED_FLAG = "\"c\":\"y\"";

    public record Tokens(String t, String hash, long expiry) {
        boolean liveAt(long now) { return now < expiry; }
    }

    private final RestTemplate restTemplate;
    private final DispatcherService dispatcherService;
    private final AdGateRepository adGateRepository;
    private final MirrorSessionProvider sessionProvider;

    private final ConcurrentHashMap<String, Tokens> byCookie = new ConcurrentHashMap<>();
    // One lock per cookie, so a slow refresh for one user never holds up another's.
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public TokenManager(RestTemplate restTemplate, DispatcherService dispatcherService,
                        AdGateRepository adGateRepository, MirrorSessionProvider sessionProvider) {
        this.restTemplate = restTemplate;
        this.dispatcherService = dispatcherService;
        this.adGateRepository = adGateRepository;
        this.sessionProvider = sessionProvider;
    }

    public String getT(String cookie) { return tokensFor(cookie).t(); }

    /**
     * The {@code t} token for the playback path, read straight from the session cookie without
     * touching the ad-gate.
     *
     * <p>{@link #getT} runs the per-play ad-gate verify (verify2.php) and 412s until a human clicks
     * a fresh ad on the device. That gate is NetMirror's per-view monetization, not what authorizes
     * the stream: playlist.php mints its own signed {@code in=} play_token inside the
     * {@code sources[].file} it returns, and the master (and its variants) fetch and play on the
     * session cookie alone — confirmed against the live mirror with the gate showing
     * {@code "Waiting for your ads click."}. {@code t} is only the {@code tm=} analytics/display
     * value on playlist.php, which the mirror accepts empty. So the playback resolver takes {@code t}
     * from the cookie's own {@code t_hash_t} (blank when it carries none, which the mirror tolerates)
     * rather than gating every single play on an ad click, which would make the player unusable.
     */
    public String playbackT(String cookie) { return sessionTokenFromCookie(cookie); }
    /** Only the alternate-video call needs this; 502 when the mirror gave none. */
    public String getHash(String cookie) {
        String hash = tokensFor(cookie).hash();
        if (hash.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The mirror did not hand out a playback hash");
        }
        return hash;
    }

    /**
     * This session's tokens, fetched again once they expire. Throws 412 when the mirror's
     * ad-gate has not been cleared for the cookie, so the app can send the user through it,
     * and 502 when the mirror answers without tokens.
     */
    public Tokens tokensFor(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The mirror session cookie is required");
        }
        Tokens cached = byCookie.get(cookie);
        if (cached != null && cached.liveAt(now())) return cached;
        synchronized (locks.computeIfAbsent(cookie, k -> new Object())) {
            cached = byCookie.get(cookie);
            if (cached != null && cached.liveAt(now())) return cached;
            try {
                Tokens fresh = load(cookie, ClientHeaderSupport.current());
                byCookie.put(cookie, fresh);
                return fresh;
            } catch (ApiException e) {
                // A 412 means the per-view ad-gate is not currently cleared — it does NOT mean the
                // session cookie is dead. That gate is NetMirror's per-view monetization and its
                // "Waiting for your ads click." (c:n) is its normal resting state; the cookie still
                // authorizes content, and playback streams on it while the gate reports uncleared
                // (confirmed live). Dropping the one shared session here would nuke a working session
                // for every caller — browse, heartbeat, playback — over nothing but that gate state,
                // which is exactly how a single detail/search request used to take the whole backend
                // to 503. So only a token-less mirror answer (502) — genuine proof the session no
                // longer works upstream — drops and re-earns it; Cloudflare challenges are handled
                // below, and the gate's own liveness is tracked by the session keep-alive/rotation.
                if (e.getStatus() == HttpStatus.BAD_GATEWAY) {
                    sessionProvider.noteRejected(cookie);
                }
                throw e;
            } catch (HttpStatusCodeException e) {
                // A Cloudflare challenge (403/503/429 with a CF fingerprint) is not a dead ad-gate: the
                // t_hash_t is still good, only the cf_clearance lapsed or Cloudflare started gating this
                // IP. Keep the session, re-mint the clearance in the background, and answer 503 so the
                // caller retries once the fresh clearance is in hand — rather than dropping a good gate.
                if (MirrorCloudflare.isChallenge(e)) {
                    sessionProvider.noteCloudflareChallenge();
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                            "The mirror's Cloudflare access is refreshing; retry shortly");
                }
                throw e;
            }
        }
    }

    /** How many sessions hold unexpired tokens. */
    public int liveSessions() {
        long now = now();
        return (int) byCookie.values().stream().filter(t -> t.liveAt(now)).count();
    }

    // Tokens are fetched when a request needs them; this only stops expired ones piling up.
    @Scheduled(fixedRate = LIFETIME_SECONDS * 1000)
    public void evictExpired() {
        long now = now();
        byCookie.entrySet().removeIf(e -> {
            boolean expired = !e.getValue().liveAt(now);
            if (expired) locks.remove(e.getKey());
            return expired;
        });
    }

    private Tokens load(String cookie, ClientHeaders client) {
        String baseUrl = dispatcherService.resolveActiveBaseUrl();

        // Step 1: Verify ad-gate
        String verifyResponse = adGateRepository.verifyAds(baseUrl, cookie, client);
        if (verifyResponse == null
                || !(verifyResponse.contains(AD_GATE_CLEARED_STATUS) || verifyResponse.contains(AD_GATE_CLEARED_FLAG))) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED,
                    "The mirror's ad-gate has not been cleared for this session");
        }

        // Step 2: Fetch tokens. The live mirror's own pages send t as the first segment of the
        // verified t_hash_t cookie; /mobile/home?app=1 was retired (404), so the verify2.php entry
        // page's data-time / data-hash attributes are only a fallback (and the sole source of hash
        // when present — the live page no longer carries one, so hash stays blank in practice).
        String t = sessionTokenFromCookie(cookie);
        String hash = "";
        Document doc = homePage(baseUrl, cookie, client);
        if (doc != null) {
            if (t.isBlank()) t = doc.body().attr("data-time");
            hash = doc.body().attr("data-hash");
        }
        if (t.isBlank()) {
            log.warn("Mirror session carried no t token (no t_hash_t cookie, no home page attributes)");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The mirror did not hand out playback tokens");
        }
        return new Tokens(t, hash, now() + LIFETIME_SECONDS);
    }

    /** The first {@code ::} segment of the verified {@code t_hash_t} cookie, or "" without one. */
    public static String sessionTokenFromCookie(String cookie) {
        for (String part : cookie.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0 || !part.substring(0, eq).trim().equals("t_hash_t")) continue;
            String value = URLDecoder.decode(part.substring(eq + 1).trim(), StandardCharsets.UTF_8);
            int sep = value.indexOf("::");
            return sep < 0 ? value : value.substring(0, sep);
        }
        return "";
    }

    private Document homePage(String baseUrl, String cookie, ClientHeaders client) {
        HttpHeaders headers = new HttpHeaders();
        ClientHeaderSupport.apply(headers, client);
        headers.set("X-Requested-With", "XMLHttpRequest");
        headers.set("Referer", baseUrl + "/");
        headers.set("Cookie", cookie);
        try {
            String html = restTemplate.exchange(baseUrl + "/mobile/verify2.php", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class).getBody();
            return Jsoup.parse(html == null ? "" : html);
        } catch (RestClientException e) {
            log.debug("Mirror home page unavailable: {}", e.getMessage());
            return null;
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }
}
