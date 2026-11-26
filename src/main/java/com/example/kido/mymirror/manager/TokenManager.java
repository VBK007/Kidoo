package com.example.kido.mymirror.manager;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.MDC;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpEntity;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.Metrics;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.MirrorCloudflare;
import com.example.kido.mymirror.MirrorRequestLimit;
import com.example.kido.mymirror.MirrorRequestContext;
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
    private static final JsonMapper VERIFICATION_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private enum Verification { VERIFIED, REQUIRED, MALFORMED, UNKNOWN, ACCESS_DENIED }

    public record Tokens(String t, String hash, long expiry) {
        boolean liveAt(long now) { return now < expiry; }
    }

    private final RestTemplate restTemplate;
    private final DispatcherService dispatcherService;
    private final AdGateRepository adGateRepository;
    private final MirrorSessionProvider sessionProvider;

    private record TokenKey(String origin, String cookie, long generation) {}

    private final ConcurrentHashMap<TokenKey, Tokens> byCookie = new ConcurrentHashMap<>();
    private record Failed(RuntimeException error, long retryAt) {}
    private final ConcurrentHashMap<TokenKey, Failed> failures = new ConcurrentHashMap<>();

    @Value("${app.mymirror.token.failure-backoff:3s}")
    private Duration failureBackoff = Duration.ofSeconds(3);

    private final ConcurrentHashMap<TokenKey, CompletableFuture<Tokens>> refreshes = new ConcurrentHashMap<>();

    public TokenManager(RestTemplate restTemplate, DispatcherService dispatcherService,
                        AdGateRepository adGateRepository, MirrorSessionProvider sessionProvider) {
        this.restTemplate = restTemplate;
        this.dispatcherService = dispatcherService;
        this.adGateRepository = adGateRepository;
        this.sessionProvider = sessionProvider;
    }

    public String getT(String cookie) { return tokensFor(cookie).t(); }

    /** The cookie-derived value used by legacy request paths. It does not establish authorization. */
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
        requireCookie(cookie);
        sessionProvider.generation(cookie);
        return tokensForOrigin(dispatcherService.resolveActiveBaseUrl(), cookie);
    }

    /** Reuse the already resolved origin when a caller's subsequent request uses the same origin. */
    public Tokens tokensForOrigin(String baseUrl, String cookie) {
        requireCookie(cookie);
        long generation = sessionProvider.generation(cookie);
        TokenKey key = new TokenKey(baseUrl, cookie, generation);
        Tokens cached = byCookie.get(key);
        if (cached != null && cached.liveAt(now())) {
            sessionProvider.ifCurrent(cookie, generation, () -> {});
            logToken("cache_hit", generation, true);
            return cached;
        }
        logToken("cache_miss", generation, null);
        CompletableFuture<Tokens> owned = new CompletableFuture<>();
        CompletableFuture<Tokens> running = refreshes.putIfAbsent(key, owned);
        if (running != null) {
            try {
                long remaining = MirrorRequestContext.deadlineWithin(Duration.ofSeconds(20)) - System.nanoTime();
                Tokens result = running.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
                sessionProvider.ifCurrent(cookie, key.generation(), () -> {});
                return result;
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof RuntimeException failure) throw failure;
                if (ex.getCause() instanceof Error failure) throw failure;
                throw new IllegalStateException(ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Token refresh interrupted");
            } catch (TimeoutException ex) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Token refresh in progress");
            }
        }

        try {
            cached = byCookie.get(key);
            if (cached == null || !cached.liveAt(now())) {
                Failed previous = failures.get(key);
                if (previous != null && System.nanoTime() < previous.retryAt()) {
                    owned.completeExceptionally(previous.error());
                    throw previous.error();
                }
                if (previous != null) failures.remove(key, previous);
                Metrics.counter("mirror.token.refresh", "outcome", "attempt").increment();
                try {
                    cached = load(baseUrl, cookie, ClientHeaderSupport.current(), generation);
                    Tokens verified = cached;
                    sessionProvider.ifCurrent(cookie, key.generation(), () -> {
                        byCookie.put(key, verified);
                        sessionProvider.observe(cookie, MirrorSessionProvider.State.VERIFIED);
                    });
                    Metrics.counter("mirror.token.refresh", "outcome", "success").increment();
                } catch (RuntimeException failure) {
                    Metrics.counter("mirror.token.refresh", "outcome", "failure").increment();
                    if (!(failure instanceof MirrorRequestLimit.Throttled)
                            && !(failure instanceof MirrorRequestLimit.AccessDenied)
                            && !(failure instanceof MirrorSessionProvider.Revoked)
                            && !accessDenied(failure) && failures.size() < 4096
                            && !failureBackoff.isNegative() && !failureBackoff.isZero()) {
                        failures.put(key, new Failed(failure, System.nanoTime() + failureBackoff.toNanos()));
                    }
                    throw failure;
                }
            }
            Tokens result = cached;
            sessionProvider.ifCurrent(cookie, key.generation(), () -> owned.complete(result));
            return result;
        } catch (RuntimeException | Error failure) {
            RuntimeException accessDenial = accessDenied(failure) ? new MirrorRequestLimit.AccessDenied() : null;
            if (accessDenial != null) {
                sessionProvider.observe(cookie, MirrorSessionProvider.State.ACCESS_DENIED);
            } else if (failure instanceof RestClientException) {
                sessionProvider.observe(cookie, MirrorSessionProvider.State.TEMPORARILY_UNAVAILABLE);
            }
            owned.completeExceptionally(accessDenial == null ? failure : accessDenial);
            if (accessDenial != null) throw accessDenial;
            throw failure;
        } finally {
            refreshes.remove(key, owned);
        }
    }

    private static void requireCookie(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The mirror session cookie is required");
        }
    }

    private static boolean accessDenied(Throwable error) {
        return error instanceof MirrorRequestLimit.AccessDenied
                || error instanceof HttpStatusCodeException response
                    && (response.getStatusCode().value() == 401 || response.getStatusCode().value() == 403)
                || MirrorCloudflare.isChallenge(error);
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
        byCookie.values().removeIf(tokens -> !tokens.liveAt(now));
        long monotonic = System.nanoTime();
        failures.values().removeIf(failure -> failure.retryAt() <= monotonic);
    }

    private Tokens load(String baseUrl, String cookie, ClientHeaders client, long generation) {
        return MirrorRequestContext.within(Duration.ofSeconds(20), () -> verifyAndLoad(baseUrl, cookie, client, generation));
    }

    private Tokens verifyAndLoad(String baseUrl, String cookie, ClientHeaders client, long generation) {
        Verification outcome;
        try {
            outcome = classifyVerification(adGateRepository.verifyAds(baseUrl, cookie, client));
        } catch (RuntimeException ex) {
            logToken(accessDenied(ex) ? Verification.ACCESS_DENIED.name() : "verification_request_failed",
                    generation, null);
            throw ex;
        }
        logToken(outcome.name(), generation, null);
        switch (outcome) {
            case REQUIRED -> throw new ApiException(HttpStatus.PRECONDITION_FAILED,
                    "The mirror's ad-gate has not been cleared for this session");
            case MALFORMED, UNKNOWN -> throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The mirror returned an unrecognized verification response");
            default -> { }
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
        logToken("token_extracted", generation, !t.isBlank());
        if (t.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The mirror did not hand out playback tokens");
        }
        return new Tokens(t, hash, now() + LIFETIME_SECONDS);
    }

    private static Verification classifyVerification(String body) {
        if (body == null || body.isBlank()) return Verification.MALFORMED;
        try {
            var root = VERIFICATION_JSON.readTree(body);
            if (!root.isObject()) return Verification.MALFORMED;
            var flag = root.path("c");
            var status = root.path("statusup");
            if ((!flag.isMissingNode() && !flag.isTextual())
                    || (!status.isMissingNode() && !status.isTextual())) return Verification.MALFORMED;
            String c = flag.asString("");
            String message = status.asString("");
            boolean required = c.equals("n") || message.equals("Waiting for your ads click.") || message.equals("NO");
            boolean cleared = c.equals("y") || message.equals("OK");
            if (required && cleared) return Verification.UNKNOWN;
            if (required) return Verification.REQUIRED;
            return cleared ? Verification.VERIFIED : Verification.UNKNOWN;
        } catch (tools.jackson.core.JacksonException ex) {
            return Verification.MALFORMED;
        }
    }

    private static void logToken(String outcome, long generation, Boolean present) {
        log.atInfo().addKeyValue("playbackAttemptId", MDC.get(MirrorRequestContext.ATTEMPT))
                .addKeyValue("tokenOutcome", outcome).addKeyValue("sessionGeneration", generation)
                .addKeyValue("tokenPresent", present).log("Mirror token decision");
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
            if (accessDenied(e)) throw new MirrorRequestLimit.AccessDenied();
            if (e instanceof HttpStatusCodeException http
                    && (http.getStatusCode().value() == 401 || http.getStatusCode().value() == 429)) throw e;
            log.debug("Mirror token page unavailable: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }
}
