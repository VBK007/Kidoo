package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import com.example.kido.mymirror.CloudflareAccessInterceptor;
import com.example.kido.mymirror.MirrorAccessProperties;

/** The Cloudflare-replay interceptor: injects a configured cf_clearance + UA, but never overrides a fresher one. */
class CloudflareAccessInterceptorTest {

    private static final String UA = "Mozilla/5.0 (Linux; Android 10; Core-T4) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36";

    /** Runs the interceptor over a request with the given starting cookie, returning the headers it sent on. */
    private static HttpHeaders run(MirrorAccessProperties access, String cookie) throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://net52.cc/mobile/home?app=1"));
        if (cookie != null) request.getHeaders().set(HttpHeaders.COOKIE, cookie);
        request.getHeaders().set(HttpHeaders.USER_AGENT, "okhttp/4.9.2");

        HttpHeaders[] sent = new HttpHeaders[1];
        ClientHttpRequestExecution execution = (req, body) -> {
            sent[0] = req.getHeaders();
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        };
        try (ClientHttpResponse ignored = new CloudflareAccessInterceptor(access).intercept(request, new byte[0], execution)) {
            return sent[0];
        }
    }

    private static MirrorAccessProperties access(String cfClearance, String ua) {
        MirrorAccessProperties a = new MirrorAccessProperties();
        a.setCfClearance(cfClearance);
        a.setBrowserUserAgent(ua);
        return a;
    }

    @Test
    void configuredClearanceAndUaAreInjectedWhenTheSessionLacksThem() throws IOException {
        HttpHeaders sent = run(access("TOKEN123", UA), "t_hash=abc; ott=nf");

        assertEquals("t_hash=abc; ott=nf; cf_clearance=TOKEN123", sent.getFirst(HttpHeaders.COOKIE));
        // cf_clearance is UA-bound, so the browser UA must replace the okhttp one.
        assertEquals(UA, sent.getFirst(HttpHeaders.USER_AGENT));
    }

    @Test
    void clearanceIsInjectedEvenWithNoExistingCookie() throws IOException {
        HttpHeaders sent = run(access("TOKEN123", ""), null);

        assertEquals("cf_clearance=TOKEN123", sent.getFirst(HttpHeaders.COOKIE));
        // No browser UA configured: leave the caller's own UA alone.
        assertEquals("okhttp/4.9.2", sent.getFirst(HttpHeaders.USER_AGENT));
    }

    @Test
    void aClearanceTheClientAlreadyHarvestedIsNotOverridden() throws IOException {
        HttpHeaders sent = run(access("SERVER_TOKEN", UA), "cf_clearance=CLIENT_TOKEN; t_hash=abc");

        assertEquals("cf_clearance=CLIENT_TOKEN; t_hash=abc", sent.getFirst(HttpHeaders.COOKIE));
        // The client's own clearance is in play, so still pin the UA it is bound to.
        assertEquals(UA, sent.getFirst(HttpHeaders.USER_AGENT));
    }

    @Test
    void withNothingConfiguredTheRequestPassesThroughUnchanged() throws IOException {
        HttpHeaders sent = run(access("", ""), "t_hash=abc");

        assertEquals("t_hash=abc", sent.getFirst(HttpHeaders.COOKIE));
        assertEquals("okhttp/4.9.2", sent.getFirst(HttpHeaders.USER_AGENT));
    }

    @Test
    void disabledMeansNoInjectionEvenWithATokenConfigured() throws IOException {
        MirrorAccessProperties a = access("TOKEN123", UA);
        a.setEnabled(false);

        HttpHeaders sent = run(a, "t_hash=abc");

        assertFalse(sent.getFirst(HttpHeaders.COOKIE).contains("cf_clearance"));
        assertEquals("t_hash=abc", sent.getFirst(HttpHeaders.COOKIE));
        assertEquals("okhttp/4.9.2", sent.getFirst(HttpHeaders.USER_AGENT));
    }

    @Test
    void aTokenWithACrLfIsRejectedNotSplitIntoHeaders() throws IOException {
        HttpHeaders sent = run(access("TOKEN\r\nX-Evil: 1", UA), "t_hash=abc");

        assertFalse(sent.getFirst(HttpHeaders.COOKIE).contains("cf_clearance"));
        assertNull(sent.getFirst("X-Evil"));
    }
}
