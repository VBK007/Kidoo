package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.HlsRewriter;
import com.example.kido.mymirror.UpstreamUrl;
import com.example.kido.mymirror.manager.TokenManager;
import com.example.kido.mymirror.repo.AdGateRepository;
import com.example.kido.mymirror.repo.DispatcherRepository;
import com.example.kido.mymirror.service.DispatcherService;

/** The mirror flow end to end against a scripted upstream: dispatcher, ad-gate, tokens, HLS. */
class MirrorFlowTest {

    private static final String BASE = "https://mirror.example";

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    // ---- Dispatcher ----

    @Test
    void dispatcherPicksTheFirstLiveMirrorAndCachesIt() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(once(), requestTo("https://mobiledetect.app/check.php"))
                .andRespond(withSuccess("{\"token_hash\":\"" + b64("https://dead.example/") + "\",\"u\":[\""
                        + b64("https://mirror.example/") + "\"]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://dead.example")).andExpect(method(HttpMethod.HEAD))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        // A mirror redirecting to its home page is up: HEAD is not followed.
        server.expect(once(), requestTo(BASE)).andExpect(method(HttpMethod.HEAD))
                .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY));

        DispatcherService dispatcher = new DispatcherService(new DispatcherRepository(rest), rest);

        assertEquals(BASE, dispatcher.resolveActiveBaseUrl());
        assertEquals(BASE, dispatcher.resolveActiveBaseUrl()); // cached: no second check.php
        server.verify();
    }

    @Test
    void dispatcherFailsLoudlyWhenNoMirrorAnswers() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://mobiledetect.app/check.php"))
                .andRespond(withSuccess("{\"token_hash\":\"" + b64("https://dead.example") + "\",\"u\":[]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://dead.example")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        DispatcherService dispatcher = new DispatcherService(new DispatcherRepository(rest), rest);

        ApiException ex = assertThrows(ApiException.class, dispatcher::resolveActiveBaseUrl);
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
    }

    // ---- Ad-gate and tokens ----

    /** A dispatcher that always answers {@link #BASE} without any network. */
    private static final class FixedDispatcher extends DispatcherService {
        FixedDispatcher() {
            super(null, null);
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
            server.expect(once(), requestTo(BASE + "/mobile/verify2.php")).andExpect(header("Cookie", cookie))
                    .andRespond(withSuccess("{\"statusup\":\"OK\"}", MediaType.APPLICATION_JSON));
            server.expect(once(), requestTo(BASE + "/mobile/home?app=1")).andExpect(header("Cookie", cookie))
                    .andRespond(withSuccess(HOME, MediaType.TEXT_HTML));
        }
        TokenManager tokens = new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest));

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
        TokenManager tokens = new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest));

        ApiException ex = assertThrows(ApiException.class, () -> tokens.getT("a=1"));
        assertEquals(HttpStatus.PRECONDITION_FAILED, ex.getStatus());
        assertEquals(0, tokens.liveSessions());
    }

    @Test
    void aHomePageWithoutTokensIsABadGateway() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo(BASE + "/mobile/verify2.php"))
                .andRespond(withSuccess("{\"statusup\":\"OK\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/mobile/home?app=1"))
                .andRespond(withSuccess("<html><body></body></html>", MediaType.TEXT_HTML));
        TokenManager tokens = new TokenManager(rest, new FixedDispatcher(), new AdGateRepository(rest));

        ApiException ex = assertThrows(ApiException.class, () -> tokens.getHash("a=1"));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
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
        assertTrue(UpstreamUrl.sameHost(URI.create("https://MIRROR.example/s/1.vtt"), BASE));
        assertFalse(UpstreamUrl.sameHost(URI.create("https://cdn.example/1.vtt"), BASE));
    }

    private static boolean isPublic(String ip) throws Exception {
        return UpstreamUrl.isPublic(InetAddress.getByName(ip));
    }
}
