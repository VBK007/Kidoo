package com.example.kido.mymirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.repo.AdGateRepository;
import com.example.kido.mymirror.repo.PlaybackRepository;
import com.example.kido.mymirror.manager.TokenManager;
import com.example.kido.mymirror.service.DispatcherService;
import com.example.kido.mymirror.service.PlaybackService;

/**
 * Phase 1 — playback host failover. Proves {@link PlaybackService#hlsMaster} recovers a host that
 * rotated out from under the 5-minute discovery cache (net52 &lt;-&gt; net77) by invalidating the
 * dead origin, rediscovering, and retrying the resolve <em>at most once</em> on the host that
 * answers now — and that it never fails over for the categories the spec forbids (throttling /
 * rate-limit penalty, access denial / Cloudflare challenge, or a content-shape error from a host
 * that did answer). The whole flow runs against a scripted upstream, so the evidence is runtime
 * behaviour, not a mock of the branch under test.
 */
class MirrorPlaybackFailoverTest {

    private static final String M1 = "https://net52.cc";
    private static final String M2 = "https://net77.cc";
    private static final String COOKIE = "t_hash_t=SESS%3A%3Am"; // session token "SESS"
    private static final String TOKEN = "SESS";
    private static final String SCOPE = "principal:a";

    private static final String PLAYLIST_42 =
            "[{\"sources\":[{\"file\":\"/mobile/hls/42.m3u8?in=PLAYTOKEN\",\"label\":\"Auto\"}]}]";
    private static final String MASTER_42 = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            720/720.m3u8
            """;
    private static final String PLAYLIST_42_PENALTY =
            "[{\"sources\":[{\"file\":\"/mobile/hls/42.m3u8?in=PENALTY\",\"label\":\"Auto\"}]}]";
    private static final String PENALTY_MASTER = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1200000
            https://s0022.freecdn4.top/s21.freecdn4.top/files/220884/720p/720p.m3u8?in=unknown::ni::m
            """;
    private static final String UNUSABLE_MASTER = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            https:///files/42/720p.m3u8
            """;

    // ---- host-availability failure recovers on the sibling host ----

    @Test
    void anUpstream5xxOnTheResolvedHostFailsOverToTheRediscoveredSiblingExactlyOnce() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();

        // The cached origin (net52) warms up fine but its playlist.php is 5xx — a Cloudflare 52x
        // "origin unreachable" shape — so the resolve fails on an available-host failure.
        expectStartup(server, M1);
        server.expect(once(), requestTo(M1 + "/mobile/playlist.php?id=42&t=&tm=" + TOKEN))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        // After the invalidate+rediscover, the sibling (net77) answers the whole flow.
        expectStartup(server, M2);
        server.expect(once(), requestTo(M2 + "/mobile/playlist.php?id=42&t=&tm=" + TOKEN))
                .andRespond(withSuccess(PLAYLIST_42, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(M2 + "/mobile/hls/42.m3u8?in=PLAYTOKEN"))
                .andRespond(withSuccess(MASTER_42, MediaType.parseMediaType(HlsRewriter.MIME_TYPE)));

        ScriptedDispatcher dispatcher = new ScriptedDispatcher(M1, M2);
        PlaybackService playback = newPlayback(rest, dispatcher);

        String master = playback.hlsMaster("42", COOKIE, SCOPE);

        assertNotNull(master);
        assertTrue(master.contains("/playback/variant?u="), "the recovered master routes its variant through the proxy");
        assertEquals(1, dispatcher.invalidations.get(), "exactly one compare-and-invalidate");
        assertEquals(M2, dispatcher.current, "discovery moved to the host that answers now");
        server.verify();
    }

    @Test
    void whenBothHostsAreDownItRetriesAtMostOnceThenSurfacesTheFailure() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();

        expectStartup(server, M1);
        server.expect(once(), requestTo(M1 + "/mobile/playlist.php?id=42&t=&tm=" + TOKEN))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        expectStartup(server, M2);
        server.expect(once(), requestTo(M2 + "/mobile/playlist.php?id=42&t=&tm=" + TOKEN))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        ScriptedDispatcher dispatcher = new ScriptedDispatcher(M1, M2);
        PlaybackService playback = newPlayback(rest, dispatcher);

        // once() on both playlist.php expectations: a second failover would hit a third playlist.php
        // and fail the mock. At-most-once keeps the retry from becoming a storm.
        assertThrows(HttpServerErrorException.class, () -> playback.hlsMaster("42", COOKIE, SCOPE));
        assertEquals(1, dispatcher.invalidations.get(), "only one failover, even with both hosts down");
        server.verify();
    }

    // ---- excluded categories never fail over (and never touch the sibling) ----

    @Test
    void aRateLimitPenaltyDoesNotFailOver() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();

        expectStartup(server, M1);
        server.expect(once(), requestTo(M1 + "/mobile/playlist.php?id=42&t=&tm=" + TOKEN))
                .andRespond(withSuccess(PLAYLIST_42_PENALTY, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(M1 + "/mobile/hls/42.m3u8?in=PENALTY"))
                .andRespond(withSuccess(PENALTY_MASTER, MediaType.parseMediaType(HlsRewriter.MIME_TYPE)));
        // No expectations scripted for M2: server.verify() proves the sibling is never touched.

        ScriptedDispatcher dispatcher = new ScriptedDispatcher(M1, M2);
        PlaybackService playback = newPlayback(rest, dispatcher);

        assertThrows(MirrorRequestLimit.Throttled.class, () -> playback.hlsMaster("42", COOKIE, SCOPE));
        assertEquals(0, dispatcher.invalidations.get(), "a penalty must not rotate hosts");
        assertEquals(M1, dispatcher.current);
        server.verify();
    }

    @Test
    void aContentShapeErrorFromAnAnsweringHostDoesNotFailOver() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();

        expectStartup(server, M1);
        server.expect(once(), requestTo(M1 + "/mobile/playlist.php?id=42&t=&tm=" + TOKEN))
                .andRespond(withSuccess(PLAYLIST_42, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(M1 + "/mobile/hls/42.m3u8?in=PLAYTOKEN"))
                .andRespond(withSuccess(UNUSABLE_MASTER, MediaType.parseMediaType(HlsRewriter.MIME_TYPE)));

        ScriptedDispatcher dispatcher = new ScriptedDispatcher(M1, M2);
        PlaybackService playback = newPlayback(rest, dispatcher);

        // A host that answered with malformed content is our own 502, not an upstream host-availability
        // failure: another host is not expected to fix bad content, so it must not fail over.
        ApiException ex = assertThrows(ApiException.class, () -> playback.hlsMaster("42", COOKIE, SCOPE));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals(0, dispatcher.invalidations.get());
        server.verify();
    }

    @Test
    void aCloudflareChallengeDuringStartupDoesNotFailOver() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();

        // The ad-gate verify is answered with a Cloudflare challenge, which becomes an access-denial.
        server.expect(once(), requestTo(M1 + "/mobile/verify2.php")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("cf-mitigated", "challenge"));

        ScriptedDispatcher dispatcher = new ScriptedDispatcher(M1, M2);
        PlaybackService playback = newPlayback(rest, dispatcher);

        ApiException ex = assertThrows(ApiException.class, () -> playback.hlsMaster("42", COOKIE, SCOPE));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals(0, dispatcher.invalidations.get(), "a challenge must not rotate hosts");
        server.verify();
    }

    // ---- DispatcherService compare-and-invalidate ----

    private static final String DISPATCH_MIRROR = "https://mirror-one.example";
    private static final String CHECK_PHP = "{\"token_hash\":\""
            + java.util.Base64.getEncoder().encodeToString(
                    "https://net52.cc/mobile/home?app=1".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + "\"}";

    @Test
    void invalidateIfCurrentOnlyDropsTheStillCurrentOrigin() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        DispatcherProperties props = new DispatcherProperties();
        props.setMirrors(List.of(DISPATCH_MIRROR));
        // Two discoveries total: the first resolve, and one more after the matching invalidate. A
        // non-matching invalidate in between must NOT trigger a re-walk.
        server.expect(once(), requestTo(DISPATCH_MIRROR + "/check.php"))
                .andRespond(withSuccess(CHECK_PHP, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(DISPATCH_MIRROR + "/check.php"))
                .andRespond(withSuccess(CHECK_PHP, MediaType.APPLICATION_JSON));

        DispatcherService dispatcher = new DispatcherService(
                new com.example.kido.mymirror.repo.DispatcherRepository(props, rest));

        assertEquals("https://net52.cc", dispatcher.resolveActiveBaseUrl());
        // A late failure naming a host that is no longer current is a no-op: the cache stands, and the
        // next resolve is served from cache without re-walking discovery.
        dispatcher.invalidateIfCurrent("https://stale-host.example");
        assertEquals("https://net52.cc", dispatcher.resolveActiveBaseUrl());
        // Naming the host that is actually current drops it, so the next resolve re-walks discovery.
        dispatcher.invalidateIfCurrent("https://net52.cc");
        assertEquals("https://net52.cc", dispatcher.resolveActiveBaseUrl());
        server.verify();
    }

    // ---- fixtures ----

    /**
     * The four-request cold warm-up before playlist.php, scripted for one host: ad-gate verify
     * (POST verify2.php) and token page (GET verify2.php), then the swallowed heartbeat (POST p.php)
     * and open-title (GET post.php). Mirrors {@code MirrorFlowTest.expectStartup} but parameterised
     * by host so the same sequence can be scripted for net52 and net77 in one failover.
     */
    private static void expectStartup(MockRestServiceServer server, String host) {
        server.expect(once(), requestTo(host + "/mobile/verify2.php")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"c\":\"y\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(host + "/mobile/verify2.php")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("<html><body></body></html>", MediaType.TEXT_HTML));
        server.expect(once(), requestTo(host + "/mobile/p.php")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"r\":\"n\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(host + "/mobile/post.php?id=42&t=" + TOKEN))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    }

    private static PlaybackService newPlayback(RestTemplate rest, DispatcherService dispatcher) {
        return new PlaybackService(new PlaybackRepository(rest, rest, rest), dispatcher,
                new TokenManager(rest, new FixedDispatcher(M1), new AdGateRepository(rest), testSessionProvider()),
                new MirrorStreamSigner(), new MirrorRequestLimit(), testSessionProvider());
    }

    private static MirrorSessionProvider testSessionProvider() {
        return new MirrorSessionProvider(new MirrorSessionProperties(),
                new AdGateRepository(new RestTemplate()), new FixedDispatcher(M1));
    }

    /** A dispatcher pinned to one host; used where the failover rotation is not under test. */
    private static final class FixedDispatcher extends DispatcherService {
        private final String host;

        FixedDispatcher(String host) {
            super(null);
            this.host = host;
        }

        @Override
        public String resolveActiveBaseUrl() {
            return host;
        }
    }

    /**
     * A dispatcher that models the live compare-and-invalidate: {@link #resolveActiveBaseUrl()} hands
     * back the current host, and {@link #invalidateIfCurrent(String)} rotates to the next scripted
     * host only when the failing base is still the current one — exactly the contract the real
     * {@link DispatcherService#invalidateIfCurrent} provides and the compare-and-invalidate unit test
     * above pins down. It counts invalidations so the tests can assert at-most-once.
     */
    private static final class ScriptedDispatcher extends DispatcherService {
        private final List<String> hosts;
        private int index;
        private volatile String current;
        final AtomicInteger invalidations = new AtomicInteger();

        ScriptedDispatcher(String... hosts) {
            super(null);
            this.hosts = List.of(hosts);
            this.current = this.hosts.get(0);
        }

        @Override
        public String resolveActiveBaseUrl() {
            return current;
        }

        @Override
        public String cachedActiveBaseUrl() {
            return current;
        }

        @Override
        public void invalidateIfCurrent(String base) {
            invalidations.incrementAndGet();
            if (base != null && base.equals(current) && index + 1 < hosts.size()) {
                current = hosts.get(++index);
            }
        }
    }
}
