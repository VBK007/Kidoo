package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.DispatcherProperties;
import com.example.kido.mymirror.HlsRewriter;
import com.example.kido.mymirror.MirrorStreamSigner;
import com.example.kido.mymirror.MirrorRequestLimit;
import com.example.kido.mymirror.MirrorSessionProperties;
import com.example.kido.mymirror.MirrorSessionProvider;
import com.example.kido.mymirror.UpstreamUrl;
import com.example.kido.mymirror.manager.TokenManager;
import com.example.kido.mymirror.repo.AdGateRepository;
import com.example.kido.mymirror.repo.DispatcherRepository;
import com.example.kido.mymirror.repo.PlaybackRepository;
import com.example.kido.mymirror.service.DispatcherService;
import com.example.kido.mymirror.service.PlaybackService;

/** The mirror flow end to end against a scripted upstream: dispatcher, ad-gate, tokens, HLS. */
class MirrorFlowTest {

    /**
     * A directly-constructed session provider for unit tests. Spawning is off by default and no
     * {@code @PostConstruct} runs here, so it holds no global session: {@code noteRejected} on the
     * 412/502 paths is a no-op and nothing opens a browser or reads disk. It satisfies
     * {@link TokenManager}'s dependency without changing the per-cookie behaviour under test.
     */
    private static MirrorSessionProvider testSessionProvider() {
        // The keep-alive is @Scheduled, so it never fires in these plain unit tests; the ad-gate and
        // dispatcher it is handed only need to exist to satisfy the constructor.
        return new MirrorSessionProvider(new MirrorSessionProperties(),
                new AdGateRepository(new RestTemplate()), new FixedDispatcher());
    }

    private static final String BASE = "https://mirror.example";

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    // ---- Dispatcher ----

    private static final String M1 = "https://mirror-one.example";
    private static final String M2 = "https://mirror-two.example";

    private static DispatcherProperties props(String... mirrors) {
        DispatcherProperties p = new DispatcherProperties();
        p.setMirrors(List.of(mirrors));
        return p;
    }

    // The real /check.php hands back the content URL (path and query included), base64-encoded; the
    // base the rest of the flow hangs off is its origin. The u[] entries are video-CDN hosts, not
    // backend candidates, so they must never be mistaken for the base.
    private static final String CHECK_PHP = "{\"token_hash\":\"" + b64("https://net52.cc/mobile/home?app=1")
            + "\",\"stape\":\"" + b64("https://net52.cc/streamtape.php")
            + "\",\"u\":[\"" + b64("https://streamtape.com") + "\"]}";

    @Test
    void dispatcherWalksPastABlockedMirrorDerivesTheOriginAndCachesIt() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        // First mirror is blocked (403, as most are in the wild); discovery must move on to the next.
        server.expect(once(), requestTo(M1 + "/check.php")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(once(), requestTo(M2 + "/check.php"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("User-Agent", "okhttp/4.9.2"))
                .andRespond(withSuccess(CHECK_PHP, MediaType.APPLICATION_JSON));

        DispatcherService dispatcher = new DispatcherService(new DispatcherRepository(props(M1, M2), rest));

        // The base is the origin of token_hash: not the whole content URL, not the u[] CDN.
        assertEquals("https://net52.cc", dispatcher.resolveActiveBaseUrl());
        assertEquals("https://net52.cc", dispatcher.resolveActiveBaseUrl()); // cached: no second walk
        server.verify();
    }

    @Test
    void dispatcherFailsLoudlyWhenNoMirrorAnswers() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(once(), requestTo(M1 + "/check.php")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(once(), requestTo(M2 + "/check.php")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        DispatcherService dispatcher = new DispatcherService(new DispatcherRepository(props(M1, M2), rest));

        ApiException ex = assertThrows(ApiException.class, dispatcher::resolveActiveBaseUrl);
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        server.verify();
    }

    // ---- Ad-gate and tokens ----

    /** A dispatcher that always answers {@link #BASE} without any network. */
    private static final class FixedDispatcher extends DispatcherService {
        FixedDispatcher() {
            super(null);
        }

        @Override
        public String resolveActiveBaseUrl() {
            return BASE;
        }
    }

    private static final String HOME = "<html><body data-time=\"T1\" data-hash=\"H1\"></body></html>";

    @Test
    void tokensAreFetchedAfterTheAdGateAndCachedPerSession() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        for (String cookie : new String[] {"a=1", "b=2"}) {
            server.expect(once(), requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.POST))
                    .andExpect(header("Cookie", cookie))
                    .andRespond(withSuccess("{ \"statusup\" : \"OK\" }", MediaType.APPLICATION_JSON));
            server.expect(once(), requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.GET))
                    .andExpect(header("Cookie", cookie))
                    .andRespond(withSuccess(HOME, MediaType.TEXT_HTML));
        }
        TokenManager tokens = new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest), testSessionProvider());

        assertEquals("T1", tokens.getT("a=1"));
        assertEquals("H1", tokens.getHash("a=1")); // same session: served from cache
        assertEquals("T1", tokens.getT("b=2"));    // another session: fetched for that cookie
        assertEquals(2, tokens.liveSessions());
        server.verify();
    }

    @Test
    void anUnclearedAdGateIsAPreconditionFailureNotANullToken() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo(BASE + "/mobile/verify2.php"))
                .andRespond(withSuccess("{\"statusup\":\"NO\"}", MediaType.APPLICATION_JSON));
        TokenManager tokens = new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest), testSessionProvider());

        ApiException ex = assertThrows(ApiException.class, () -> tokens.getT("a=1"));
        assertEquals(HttpStatus.PRECONDITION_FAILED, ex.getStatus());
        assertEquals(0, tokens.liveSessions());
    }

    @Test
    void requiredAuthorizationStopsMasterBeforeAnyStartupRequests() {
        for (String body : List.of(
                "{\"c\":\"n\",\"statusup\":\"Waiting for your ads click.\"}",
                "{\"wrapper\":{\"c\":\"y\"}}",
                "{\"c\":\"y\"} trailing",
                "{\"c\":\"n\",\"statusup\":\"OK\"}",
                "{\"c\":\"n\",\"c\":\"y\"}")) {
            RestTemplate rest = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
            server.expect(once(), requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            var failure = assertThrows(ApiException.class,
                    () -> newPlayback(rest).hlsMaster("42", "t_hash_t=fixture"));
            assertEquals(body.contains("Waiting") ? HttpStatus.PRECONDITION_FAILED : HttpStatus.BAD_GATEWAY,
                    failure.getStatus());
            server.verify();
        }
    }

    @Test
    void distinguishesMasterMediaMalformedAndAmbiguousPlaylists() {
        URI base = URI.create(BASE + "/master.m3u8");
        String variant = "#EXT-X-STREAM-INF:BANDWIDTH=1000\nvariant.m3u8?label=unknown\n";
        String segment = "#EXTINF:4,\nsegment.ts\n";
        assertEquals(HlsRewriter.PlaylistKind.MASTER, HlsRewriter.playlistKind("#EXTM3U\n" + variant, base));
        assertEquals(HlsRewriter.PlaylistKind.MEDIA, HlsRewriter.playlistKind("#EXTM3U\n" + segment, base));
        assertEquals(HlsRewriter.PlaylistKind.AMBIGUOUS,
                HlsRewriter.playlistKind("#EXTM3U\n" + variant + segment, base));
        for (String body : List.of("", "Forbidden", "#EXTM3U\nloose.ts",
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=0\nvariant.m3u8",
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps:///variant.m3u8",
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1")) {
            assertEquals(HlsRewriter.PlaylistKind.MALFORMED, HlsRewriter.playlistKind(body, base));
        }
    }

    @Test
    void ambiguousRestrictionMarkerStillBlocksAndStartsCooldown() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        expectStartup(server, "SESS");
        server.expect(once(), requestTo(BASE + "/mobile/playlist.php?id=42&t=&tm=SESS"))
                .andRespond(withSuccess("[{\"file\":\"/master.m3u8\"}]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/master.m3u8")).andRespond(withSuccess("""
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000
                variant.m3u8
                # https://fixture.example/files/220884/notice.m3u8?note=in=unknown
                """, MediaType.parseMediaType(HlsRewriter.MIME_TYPE)));
        var limit = new MirrorRequestLimit();
        var sessions = testSessionProvider();
        var playback = new PlaybackService(new PlaybackRepository(rest, rest, rest), new FixedDispatcher(),
                new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest), sessions),
                new MirrorStreamSigner(), limit, sessions);
        assertThrows(MirrorRequestLimit.Throttled.class, () -> playback.hlsMaster("42", "t_hash_t=SESS"));
        assertThrows(MirrorRequestLimit.Throttled.class, limit::checkCooldown);
        server.verify();
    }

    @Test
    void fastTokenCacheHitChecksGenerationAgainBeforeReturning() {
        RestTemplate rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(once(), requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"c\":\"y\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(HOME, MediaType.TEXT_HTML));
        var sessions = org.mockito.Mockito.spy(testSessionProvider());
        var manager = new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest), sessions);
        String cookie = "t_hash_t=fixture";
        assertEquals("fixture", manager.getT(cookie));
        org.mockito.Mockito.doAnswer(call -> {
            sessions.noteRejected(cookie);
            return call.callRealMethod();
        }).when(sessions).ifCurrent(org.mockito.ArgumentMatchers.eq(cookie),
                org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.any(Runnable.class));
        assertThrows(MirrorSessionProvider.Revoked.class, () -> manager.tokensForOrigin(BASE, cookie));
        server.verify();
    }

    @Test
    void aHomePageWithoutTokensIsABadGateway() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"statusup\":\"OK\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("<html><body></body></html>", MediaType.TEXT_HTML));
        TokenManager tokens = new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest), testSessionProvider());

        ApiException ex = assertThrows(ApiException.class, () -> tokens.getHash("a=1"));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
    }

    // What the live mirror does today: the entry page hands out no hash and t rides in the
    // verified cookie. (And if even the entry page is briefly 404, t still comes from the cookie.)
    @Test
    void tIsTakenFromTheVerifiedCookieWhenTheHomePageIsGone() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        String cookie = "addhash=X%3A%3Ani; t_hash_t=abc123%3A%3Adef456%3A%3Am";
        server.expect(requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"c\":\"y\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        TokenManager tokens = new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest), testSessionProvider());

        assertEquals("abc123", tokens.getT(cookie));
        // No page handed out a hash, so only the hash-dependent calls fail, and say why.
        ApiException ex = assertThrows(ApiException.class, () -> tokens.getHash(cookie));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        server.verify();
    }

    @Test
    void sessionTokenIsTheFirstSegmentOfTHashT() {
        assertEquals("abc", TokenManager.sessionTokenFromCookie("t_hash_t=abc::m"));
        assertEquals("abc", TokenManager.sessionTokenFromCookie("x=1; t_hash_t=abc%3A%3Am; y=2"));
        assertEquals("", TokenManager.sessionTokenFromCookie("addhash=abc::ni"));
        assertEquals("", TokenManager.sessionTokenFromCookie("not_t_hash_t=abc::m"));
    }

    // ---- HLS ----

    @Test
    void hlsEntriesAreMadeAbsoluteAgainstTheMirrorPlaylist() {
        URI master = URI.create(BASE + "/mobile/hls/42.m3u8?in=T1");
        String playlist = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Tamil",URI="audio/ta.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO="aud"
                720/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=400000
                https://cdn.example/480/index.m3u8
                """;

        String out = HlsRewriter.absolutize(playlist, master);

        assertEquals("""
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Tamil",URI="https://mirror.example/mobile/hls/audio/ta.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO="aud"
                https://mirror.example/mobile/hls/720/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=400000
                https://cdn.example/480/index.m3u8
                """, out);
    }

    @Test
    void theHlsMasterIsFetchedAtThePlaylistsSignedSourceNotTheSessionToken() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        String cookie = "t_hash_t=SESS%3A%3Am"; // session token "SESS"
        // The lead-in uses the normal heartbeat token flow before requesting a signed source.
        // playlist.php is asked with the session token as tm= and a blank title; it answers signed
        // sources whose in= is the SEPARATE play_token. "Auto" is the multi-variant master; the
        // q=720p "default" entry is a single rendition and must not be the one chosen.
        expectStartup(server, "SESS");
        server.expect(once(), requestTo(BASE + "/mobile/playlist.php?id=42&t=&tm=SESS")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"sources":[
                          {"file":"/mobile/hls/42.m3u8?in=PLAYTOKEN&hd=off&lang=eng&hp=yes","label":"Auto"},
                          {"file":"/mobile/hls/42.m3u8?q=720p&in=PLAYTOKEN","label":"Mid HD","default":"true"}
                        ]}]""", MediaType.APPLICATION_JSON));
        // The master is fetched at the signed Auto URL — in=PLAYTOKEN, never in=SESS.
        server.expect(once(), requestTo(BASE + "/mobile/hls/42.m3u8?in=PLAYTOKEN&hd=off&lang=eng&hp=yes"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=800000
                        720/720.m3u8
                        """, MediaType.parseMediaType(HlsRewriter.MIME_TYPE)));

        PlaybackService playback = new PlaybackService(new PlaybackRepository(rest, rest, rest), new FixedDispatcher(),
                new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest), testSessionProvider()),
                new MirrorStreamSigner(), new MirrorRequestLimit(), testSessionProvider());

        String master = playback.hlsMaster("42", cookie);

        // The variant is not emitted as a bare CDN URL: the segment CDN gates each variant playlist on
        // the mirror Referer, so the master routes every variant through this server's signed
        // /playback/variant proxy (see PlaybackService#variantProxyPath). The absolute URL — made
        // absolute against the signed master URL — rides inside the proxy token (u= is plain URL-safe
        // base64 of "<baseUrl>\n<upstreamUrl>"), so decode it and confirm the resolved variant URL.
        String marker = "/playback/variant?u=";
        int u = master.indexOf(marker);
        assertTrue(u >= 0, "the variant must be routed through the signed /playback/variant proxy");
        int tokenStart = u + marker.length();
        int tokenEnd = master.indexOf("&s=", tokenStart);
        assertTrue(tokenEnd > tokenStart, "the proxied variant must carry a signature");
        String payload = new String(Base64.getUrlDecoder().decode(master.substring(tokenStart, tokenEnd)),
                StandardCharsets.UTF_8);
        assertTrue(payload.contains("https://mirror.example/mobile/hls/720/720.m3u8"),
                "the proxied variant must carry the URL made absolute against the signed master URL");
        // The session token never leaks into the stream URL — the stream is authorized by the signed
        // play_token (in=PLAYTOKEN) fetched at playlist.php, not by the session token, which this route
        // uses only as playlist.php's tm= display param (asserted on the playlist.php request above).
        assertFalse(payload.contains("SESS"), "the session token must never appear in the stream URL");
        server.verify();
    }

    @Test
    void aRateLimitPenaltyMasterIsSurfacedHonestlyAndNotPlayedOrRetried() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        String cookie = "t_hash_t=SESS%3A%3Am";
        // When the shared upstream session is rate-limited, net52.cc returns a 200 master whose
        // rendition is the "STOP ABUSE — too many requests" penalty clip, marked in=unknown (no real
        // play_token). It is a valid m3u8 on an allowed CDN host, so without detection the player would
        // stream the notice as if it were the title.
        expectStartup(server, "SESS");
        server.expect(once(), requestTo(BASE + "/mobile/playlist.php?id=42&t=&tm=SESS")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"sources":[{"file":"/mobile/hls/42.m3u8?in=PENALTY","label":"Auto"}]}]""",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/hls/42.m3u8?in=PENALTY")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=1200000
                        https://s0022.freecdn4.top/s21.freecdn4.top/files/220884/720p/720p.m3u8?in=unknown::ni::m
                        """, MediaType.parseMediaType(HlsRewriter.MIME_TYPE)));

        PlaybackService playback = new PlaybackService(new PlaybackRepository(rest, rest, rest), new FixedDispatcher(),
                new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest), testSessionProvider()),
                new MirrorStreamSigner(), new MirrorRequestLimit(), testSessionProvider());

        ApiException ex = assertThrows(ApiException.class, () -> playback.hlsMaster("42", cookie));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        // once() on both expectations: a retry would hit playlist.php a second time and fail the mock,
        // proving the penalty short-circuits the warm-up loop rather than amplifying the load that
        // tripped the cap.
        server.verify();
    }

    // ---- HLS: single-flight + short success cache (see PlaybackService#hlsMaster) ----

    private static void expectStartup(MockRestServiceServer server, String token) {
        server.expect(once(), requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"c\":\"y\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("<html><body></body></html>", MediaType.TEXT_HTML));
        server.expect(once(), requestTo(BASE + "/mobile/p.php")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"r\":\"n\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/post.php?id=42&t=" + token))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    }

    private static PlaybackService newPlayback(RestTemplate rest) {
        return new PlaybackService(new PlaybackRepository(rest, rest, rest), new FixedDispatcher(),
                new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest), testSessionProvider()),
                new MirrorStreamSigner(), new MirrorRequestLimit(), testSessionProvider());
    }

    // A normal (non-penalty) play of id 42: the playlist.php body and the master it resolves to.
    private static final String PLAYLIST_42 =
            "[{\"sources\":[{\"file\":\"/mobile/hls/42.m3u8?in=PLAYTOKEN\",\"label\":\"Auto\"}]}]";
    private static final String MASTER_42 = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            720/720.m3u8
            """;
    // The rate-limited variant: playlist.php still signs a source, but the master it resolves to is the
    // in=unknown "STOP ABUSE" penalty clip, which PlaybackService surfaces as a 429.
    private static final String PLAYLIST_42_PENALTY =
            "[{\"sources\":[{\"file\":\"/mobile/hls/42.m3u8?in=PENALTY\",\"label\":\"Auto\"}]}]";
    private static final String PENALTY_MASTER = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1200000
            https://s0022.freecdn4.top/s21.freecdn4.top/files/220884/720p/720p.m3u8?in=unknown::ni::m
            """;

    @Test
    void aFreshlyResolvedMasterIsReusedWithinItsTtlWithoutReHittingUpstream() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        String cookie = "t_hash_t=SESS%3A%3Am";
        expectStartup(server, "SESS");
        server.expect(once(), requestTo(BASE + "/mobile/playlist.php?id=42&t=&tm=SESS")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(PLAYLIST_42, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/hls/42.m3u8?in=PLAYTOKEN")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(MASTER_42, MediaType.parseMediaType(HlsRewriter.MIME_TYPE)));

        PlaybackService playback = newPlayback(rest);
        String first = playback.hlsMaster("42", cookie);
        String second = playback.hlsMaster("42", cookie); // a re-open/manifest refresh within the cache TTL

        // once() on both upstream calls: a second resolution would hit playlist.php again and fail the
        // mock. The second call returns the very object the first cached, not a re-resolved copy.
        assertSame(first, second);
        server.verify();
    }

    @Test
    void concurrentPlaysOfOneTitleCoalesceToASingleUpstreamResolution() throws Exception {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        String cookie = "t_hash_t=SESS%3A%3Am";

        // Hold the owner's playlist.php response open so the other callers provably arrive while its
        // resolution is still in flight — the single-flight path, which the TTL cache alone would also
        // absorb if they arrived after. Only the owning thread ever reaches the mock; the rest park on
        // the shared future, so the mock stays single-threaded exactly when coalescing works.
        CountDownLatch ownerInFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        expectStartup(server, "SESS");
        server.expect(once(), requestTo(BASE + "/mobile/playlist.php?id=42&t=&tm=SESS")).andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    ownerInFlight.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS), "owner resolution should be released");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return withSuccess(PLAYLIST_42, MediaType.APPLICATION_JSON).createResponse(request);
                });
        server.expect(once(), requestTo(BASE + "/mobile/hls/42.m3u8?in=PLAYTOKEN")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(MASTER_42, MediaType.parseMediaType(HlsRewriter.MIME_TYPE)));

        PlaybackService playback = newPlayback(rest);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Future<String> owner = pool.submit(() -> playback.hlsMaster("42", cookie));
            assertTrue(ownerInFlight.await(5, TimeUnit.SECONDS), "owner should reach upstream");

            List<Future<String>> joiners = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                joiners.add(pool.submit(() -> playback.hlsMaster("42", cookie)));
            }
            release.countDown();

            String expected = owner.get(5, TimeUnit.SECONDS);
            assertNotNull(expected);
            for (Future<String> joiner : joiners) {
                assertSame(expected, joiner.get(5, TimeUnit.SECONDS), "every caller shares the one resolution");
            }
        } finally {
            pool.shutdownNow();
        }
        // once() on both upstream calls: if any of the eight callers resolved independently it would have
        // issued a second playlist.php and failed the mock. Single-flight funnels them to one resolution.
        server.verify();
    }

    @Test
    void aPenaltyStopsImmediateRetriesEvenForAnotherTitle() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        String cookie = "t_hash_t=SESS%3A%3Am";
        expectStartup(server, "SESS");
        server.expect(once(), requestTo(BASE + "/mobile/playlist.php?id=42&t=&tm=SESS"))
                .andRespond(withSuccess(PLAYLIST_42_PENALTY, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/hls/42.m3u8?in=PENALTY"))
                .andRespond(withSuccess(PENALTY_MASTER, MediaType.parseMediaType(HlsRewriter.MIME_TYPE)));
        PlaybackService playback = newPlayback(rest);

        for (String id : List.of("42", "42", "43")) {
            MirrorRequestLimit.Throttled ex = assertThrows(MirrorRequestLimit.Throttled.class,
                    () -> playback.hlsMaster(id, cookie));
            assertTrue(ex.retryAfterSeconds() > 0);
        }
        server.verify();
    }

    @Test
    void anUnusableMasterDoesNotRemintThePlaybackToken() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        expectStartup(server, "SESS");
        server.expect(once(), requestTo(BASE + "/mobile/playlist.php?id=42&t=&tm=SESS"))
                .andRespond(withSuccess(PLAYLIST_42, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/hls/42.m3u8?in=PLAYTOKEN"))
                .andRespond(withSuccess("""
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=800000
                        https:///files/42/720p.m3u8
                        """, MediaType.parseMediaType(HlsRewriter.MIME_TYPE)));

        ApiException ex = assertThrows(ApiException.class,
                () -> newPlayback(rest).hlsMaster("42", "t_hash_t=SESS%3A%3Am"));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        server.verify();
    }

    @Test
    void upstreamRetryAfterBlocksRequestsOnBothClients() {
        MirrorRequestLimit limit = new MirrorRequestLimit();
        RestTemplate mirror = new RestTemplate();
        RestTemplate cdn = new RestTemplate();
        mirror.getInterceptors().add(limit);
        cdn.getInterceptors().add(limit);
        MockRestServiceServer mirrorServer = MockRestServiceServer.bindTo(mirror).build();
        MockRestServiceServer cdnServer = MockRestServiceServer.bindTo(cdn).build();
        mirrorServer.expect(once(), requestTo(BASE + "/limited"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "120"));

        MirrorRequestLimit.Throttled first = assertThrows(MirrorRequestLimit.Throttled.class,
                () -> mirror.getForObject(BASE + "/limited", String.class));
        assertEquals(120, first.retryAfterSeconds());
        assertThrows(MirrorRequestLimit.Throttled.class,
                () -> cdn.getForObject(BASE + "/variant", String.class));
        mirrorServer.verify();
        cdnServer.verify();
    }

    @Test
    void backgroundBudgetNeverQueuesBehindForegroundOrExceedsItsDispatchLimit() throws Exception {
        var limit = new MirrorRequestLimit();
        var rest = new RestTemplate();
        rest.getInterceptors().add(limit);
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var mock = MockRestServiceServer.bindTo(rest).build();
        mock.expect(once(), requestTo(BASE + "/foreground")).andRespond(req -> {
            entered.countDown();
            try {
                if (!released.await(3, TimeUnit.SECONDS)) throw new java.io.IOException("fixture deadline");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(ex);
            }
            return withSuccess("foreground", MediaType.TEXT_PLAIN).createResponse(req);
        });
        mock.expect(once(), requestTo(BASE + "/background"))
                .andRespond(withSuccess("background", MediaType.TEXT_PLAIN));
        var budget = new java.util.concurrent.atomic.AtomicInteger(1);
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var foreground = worker.submit(() -> rest.getForObject(BASE + "/foreground", String.class));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertThrows(MirrorRequestLimit.Busy.class, () ->
                        com.example.kido.mymirror.MirrorRequestContext.background(budget,
                                () -> rest.getForObject(BASE + "/unused", String.class)));
                assertEquals(1, budget.get());
            } finally {
                released.countDown();
            }
            assertEquals("foreground", foreground.get(3, TimeUnit.SECONDS));
            assertEquals("background", com.example.kido.mymirror.MirrorRequestContext.background(budget,
                    () -> rest.getForObject(BASE + "/background", String.class)));
            assertThrows(MirrorRequestLimit.Busy.class, () ->
                    com.example.kido.mymirror.MirrorRequestContext.background(budget,
                            () -> rest.getForObject(BASE + "/over-budget", String.class)));
            limit.throttled("60");
            assertThrows(MirrorRequestLimit.Throttled.class, () ->
                    com.example.kido.mymirror.MirrorRequestContext.background(
                            new java.util.concurrent.atomic.AtomicInteger(1),
                            () -> rest.getForObject(BASE + "/under-cooldown", String.class)));
        }
        mock.verify();
    }

    @Test
    void masterCacheDoesNotReuseAnotherSessionsResult() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        for (String token : List.of("SESS", "OTHER")) {
            expectStartup(server, token);
            server.expect(once(), requestTo(BASE + "/mobile/playlist.php?id=42&t=&tm=" + token))
                    .andRespond(withSuccess(PLAYLIST_42, MediaType.APPLICATION_JSON));
            server.expect(once(), requestTo(BASE + "/mobile/hls/42.m3u8?in=PLAYTOKEN"))
                    .andRespond(withSuccess(MASTER_42, MediaType.parseMediaType(HlsRewriter.MIME_TYPE)));
        }

        PlaybackService playback = newPlayback(rest);
        playback.hlsMaster("42", "t_hash_t=SESS%3A%3Am");
        playback.hlsMaster("42", "t_hash_t=OTHER%3A%3Am");
        server.verify();
    }

    @Test
    void aChallengeDuringStartupEndsTheAttemptWithoutAnotherRequest() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(once(), requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("cf-mitigated", "challenge"));

        ApiException failure = assertThrows(ApiException.class,
                () -> newPlayback(rest).hlsMaster("42", "t_hash_t=SESS%3A%3Am"));
        assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
        server.verify();
    }

    @Test
    void cacheDoesNotReuseTheGlobalSessionResultAcrossPrincipals() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        String cookie = "t_hash_t=SESS%3A%3Am";
        expectStartup(server, "SESS");
        expectMovie(server, "FIRST");
        server.expect(once(), requestTo(BASE + "/mobile/p.php"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/post.php?id=42&t=SESS"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        expectMovie(server, "SECOND");

        PlaybackService playback = newPlayback(rest);
        assertNotNull(playback.hlsMaster("42", cookie, "principal:a"));
        assertNotNull(playback.hlsMaster("42", cookie, "principal:b"));
        server.verify();
    }

    private static void expectMovie(MockRestServiceServer server, String signedToken) {
        server.expect(once(), requestTo(BASE + "/mobile/playlist.php?id=42&t=&tm=SESS"))
                .andRespond(withSuccess(PLAYLIST_42.replace("PLAYTOKEN", signedToken), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/hls/42.m3u8?in=" + signedToken))
                .andRespond(withSuccess(MASTER_42, MediaType.parseMediaType(HlsRewriter.MIME_TYPE)));
    }

    @Test
    void tokenRefreshSurvivesEvictionAndCoalescesConcurrentCallers() throws Exception {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.expect(once(), requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    started.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                    return withSuccess("{\"c\":\"y\"}", MediaType.APPLICATION_JSON).createResponse(request);
                });
        server.expect(once(), requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(HOME, MediaType.TEXT_HTML));
        TokenManager manager = new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest),
                testSessionProvider());
        String cookie = "t_hash_t=SESS%3A%3Am";

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TokenManager.Tokens> owner = workers.submit(() -> manager.tokensFor(cookie));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                manager.evictExpired();
                Future<TokenManager.Tokens> follower = workers.submit(() -> manager.tokensFor(cookie));
                release.countDown();
                assertSame(owner.get(5, TimeUnit.SECONDS), follower.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
        server.verify();
    }

    @Test
    void sameContentIdFromAnotherCatalogueGetsItsOwnMaster() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        String cookie = "t_hash_t=SESS%3A%3Am";
        expectStartup(server, "SESS");
        expectMovie(server, "NETFLIX");

        server.expect(once(), requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"c\":\"y\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/verify2.php")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(HOME, MediaType.TEXT_HTML));
        server.expect(once(), requestTo(BASE + "/mobile/p.php"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/hs/post.php?id=42&t=SESS"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/hs/playlist.php?id=42&t=&tm=SESS"))
                .andRespond(withSuccess(PLAYLIST_42.replace("PLAYTOKEN", "HOTSTAR"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/mobile/hls/42.m3u8?in=HOTSTAR"))
                .andRespond(withSuccess(MASTER_42, MediaType.parseMediaType(HlsRewriter.MIME_TYPE)));

        PlaybackService playback = newPlayback(rest);
        assertNotNull(playback.hlsMaster("42", cookie + "; ott=nf"));
        assertNotNull(playback.hlsMaster("42", cookie + "; ott=dp"));
        server.verify();
    }

    @Test
    void sharesTransientFailureAfterRefreshCompletes() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(once(), requestTo(BASE + "/mobile/verify2.php"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        var manager = new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest),
                testSessionProvider());
        String cookie = "t_hash_t=fixture";
        var first = assertThrows(org.springframework.web.client.RestClientException.class,
                () -> manager.tokensFor(cookie));
        var repeated = assertThrows(org.springframework.web.client.RestClientException.class,
                () -> manager.tokensFor(cookie));
        assertSame(first, repeated);
        server.verify();
    }

    // ---- URLs ----

    @Test
    void userTextStaysInsideItsQueryParameter() {
        URI uri = UpstreamUrl.of(BASE, "/mobile/search.php", "s", "tom & jerry=1", "t", "T1");

        assertEquals(BASE + "/mobile/search.php?s=tom+%26+jerry%3D1&t=T1", uri.toString());
        assertEquals("..%2Fetc", UpstreamUrl.segment("../etc"));
    }

    @Test
    void subtitleUrlsMustBePublicHttp() throws Exception {
        for (String bad : new String[] {"file:///etc/passwd", "ftp://x.example/a.vtt", "http://127.0.0.1/a",
                "http://localhost:8080/actuator", "http://192.168.1.5/a.vtt", "http://169.254.169.254/latest",
                "not a url", null}) {
            ApiException ex = assertThrows(ApiException.class, () -> UpstreamUrl.requirePublicHttp(bad), "url=" + bad);
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        }
        // Checked on resolved addresses, so these hold without depending on DNS.
        assertTrue(isPublic("8.8.8.8"));
        assertFalse(isPublic("10.0.0.1"));
        assertFalse(isPublic("100.100.1.1"));
        assertFalse(isPublic("fd00::1"));
    }

    @Test
    void subtitleCookieGoesOnlyToTheMirrorHost() {
        assertTrue(UpstreamUrl.sameOrigin(URI.create("https://MIRROR.example:443/s/1.vtt"), BASE));
        assertFalse(UpstreamUrl.sameOrigin(URI.create("https://cdn.example/1.vtt"), BASE));
        assertFalse(UpstreamUrl.sameOrigin(URI.create("http://mirror.example/1.vtt"), BASE));
        assertFalse(UpstreamUrl.sameOrigin(URI.create("https://mirror.example:8443/1.vtt"), BASE));
    }

    private static boolean isPublic(String ip) throws Exception {
        return UpstreamUrl.isPublic(InetAddress.getByName(ip));
    }
}
