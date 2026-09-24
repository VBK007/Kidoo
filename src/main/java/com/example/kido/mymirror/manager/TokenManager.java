package com.example.kido.mymirror.manager;

import java.util.concurrent.ConcurrentHashMap;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.model.ClientHeaders;
import com.example.kido.mymirror.repo.AdGateRepository;
import com.example.kido.mymirror.service.DispatcherService;

import lombok.extern.slf4j.Slf4j;

/**
 * The {@code t} and {@code hash} tokens the mirror hands out on its home page, one pair per
 * user session. The site ties them to the cookie (and device) that cleared its ad-gate, so a
 * pair fetched for one user is no good to another.
 */
@Slf4j
@Component
public class TokenManager {
    private static final long LIFETIME_SECONDS = 180;
    private static final String AD_GATE_CLEARED = "\"statusup\":\"OK\"";

    public record Tokens(String t, String hash, long expiry) {
        boolean liveAt(long now) { return now < expiry; }
    }

    private final RestTemplate restTemplate;
    private final DispatcherService dispatcherService;
    private final AdGateRepository adGateRepository;

    private final ConcurrentHashMap<String, Tokens> byCookie = new ConcurrentHashMap<>();
    // One lock per cookie, so a slow refresh for one user never holds up another's.
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public TokenManager(RestTemplate restTemplate, DispatcherService dispatcherService,
                        AdGateRepository adGateRepository) {
        this.restTemplate = restTemplate;
        this.dispatcherService = dispatcherService;
        this.adGateRepository = adGateRepository;
    }

    public String getT(String cookie) { return tokensFor(cookie).t(); }
    public String getHash(String cookie) { return tokensFor(cookie).hash(); }

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
            Tokens fresh = load(cookie, ClientHeaderSupport.current());
            byCookie.put(cookie, fresh);
            return fresh;
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
        if (verifyResponse == null || !verifyResponse.contains(AD_GATE_CLEARED)) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED,
                    "The mirror's ad-gate has not been cleared for this session");
        }

        // Step 2: Fetch tokens
        HttpHeaders headers = new HttpHeaders();
        ClientHeaderSupport.apply(headers, client);
        headers.set("X-Requested-With", "XMLHttpRequest");
        headers.set("Referer", baseUrl + "/mobile/home?app=1");
        headers.set("Cookie", cookie);

        HttpEntity<String> request = new HttpEntity<>(headers);
        String html = restTemplate.exchange(baseUrl + "/mobile/home?app=1", HttpMethod.GET, request, String.class).getBody();
        Document doc = Jsoup.parse(html == null ? "" : html);

        String t = doc.body().attr("data-time");
        String hash = doc.body().attr("data-hash");
        if (t.isBlank() || hash.isBlank()) {
            log.warn("Mirror home page carried no tokens");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The mirror did not hand out playback tokens");
        }
        return new Tokens(t, hash, now() + LIFETIME_SECONDS);
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }
}
