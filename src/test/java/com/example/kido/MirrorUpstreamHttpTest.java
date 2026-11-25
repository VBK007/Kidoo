package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientException;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.DispatcherProperties;
import com.example.kido.mymirror.MirrorAccessProperties;
import com.example.kido.mymirror.MirrorHttpConfig;
import com.example.kido.mymirror.MirrorRequestLimit;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class MirrorUpstreamHttpTest {
    private HttpServer upstream;
    private HttpServer thirdParty;
    private String approved;
    private String external;
    private final MirrorHttpConfig config = new MirrorHttpConfig();
    private final MirrorRequestLimit limit = new MirrorRequestLimit();

    @BeforeEach
    void startServers() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        thirdParty = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.start();
        thirdParty.start();
        approved = "http://127.0.0.1:" + upstream.getAddress().getPort();
        external = "http://127.0.0.1:" + thirdParty.getAddress().getPort();
    }

    @AfterEach
    void stopServers() {
        if (upstream != null) upstream.stop(0);
        if (thirdParty != null) thirdParty.stop(0);
    }

    @Test
    void limitHoldsUntilBodyCompletesAcrossBothClients() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var otherCalls = new AtomicInteger();
        upstream.createContext("/slow", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write('o');
                exchange.getResponseBody().flush();
                started.countDown();
                if (finish.await(8, TimeUnit.SECONDS)) exchange.getResponseBody().write('k');
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        thirdParty.createContext("/variant", exchange -> {
            otherCalls.incrementAndGet();
            reply(exchange, 200, "variant");
        });

        var origin = config.restTemplate(access(), limit, new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(com.example.kido.mymirror.MirrorSessionProvider.class));
        var cdn = config.cdnRestTemplate(limit);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> origin.getForObject(approved + "/slow", String.class));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(MirrorRequestLimit.Busy.class,
                        () -> cdn.getForObject(external + "/variant", String.class));
                assertEquals(0, otherCalls.get());
            } finally {
                finish.countDown();
            }
            assertEquals("ok", first.get(5, TimeUnit.SECONDS));
            assertEquals("variant", cdn.getForObject(external + "/variant", String.class));
        }
    }

    @Test
    void provider429PreventsFollowUpThroughAnotherClient() {
        var calls = new AtomicInteger();
        upstream.createContext("/limited", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().set("Retry-After", "120");
            reply(exchange, 429, "");
        });
        var origin = config.restTemplate(access(), limit, new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(com.example.kido.mymirror.MirrorSessionProvider.class));
        var discovery = config.mirrorDiscoveryRestTemplate(limit, new DispatcherProperties());
        var error = assertThrows(MirrorRequestLimit.Throttled.class,
                () -> origin.getForObject(approved + "/limited", String.class));
        assertEquals(120, error.retryAfterSeconds());
        assertThrows(MirrorRequestLimit.Throttled.class,
                () -> discovery.getForObject(approved + "/limited", String.class));
        assertEquals(1, calls.get());
    }

    @Test
    void unapprovedOriginsAndRedirectsNeverReceiveCredentials() {
        var credentialed = new AtomicReference<Headers>();
        var uncredentialed = new AtomicReference<Headers>();
        upstream.createContext("/redirect", exchange -> {
            credentialed.set(exchange.getRequestHeaders());
            exchange.getResponseHeaders().set("Location", external + "/sink");
            reply(exchange, 302, "");
        });
        thirdParty.createContext("/sink", exchange -> {
            uncredentialed.set(exchange.getRequestHeaders());
            reply(exchange, 200, "safe");
        });
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, "t_hash_t=fixture");
        headers.setBearerAuth("fixture");
        headers.set(HttpHeaders.PROXY_AUTHORIZATION, "fixture");
        var request = new HttpEntity<Void>(headers);
        var origin = config.restTemplate(access(), limit, new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(com.example.kido.mymirror.MirrorSessionProvider.class));

        assertThrows(ApiException.class, () -> origin.exchange(approved + "/redirect", HttpMethod.GET,
                request, String.class));
        assertNotNull(credentialed.get());
        assertTrue(credentialed.get().getFirst(HttpHeaders.COOKIE).contains("cf_clearance=fixture"));
        assertThrows(ApiException.class, () -> origin.exchange(external + "/sink", HttpMethod.GET,
                request, String.class));
        assertNull(uncredentialed.get());

        config.subtitleRestTemplate().exchange(external + "/sink", HttpMethod.GET, request, String.class);
        assertNotNull(uncredentialed.get());
        assertFalse(uncredentialed.get().containsKey(HttpHeaders.COOKIE));
        assertFalse(uncredentialed.get().containsKey(HttpHeaders.AUTHORIZATION));
        assertFalse(uncredentialed.get().containsKey(HttpHeaders.PROXY_AUTHORIZATION));
    }

    @Test
    void responseBodyCannotExtendTheDeadline() {
        var calls = new AtomicInteger();
        var finish = new CountDownLatch(1);
        upstream.createContext("/stall", exchange -> {
            calls.incrementAndGet();
            try (exchange) {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write('a');
                exchange.getResponseBody().flush();
                finish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        var props = new DispatcherProperties();
        props.setTimeout(Duration.ofMillis(750));
        long began = System.nanoTime();
        try {
            assertThrows(RestClientException.class, () -> config.mirrorDiscoveryRestTemplate(limit, props)
                    .getForObject(approved + "/stall", String.class));
            assertTrue(Duration.ofNanos(System.nanoTime() - began).compareTo(Duration.ofSeconds(4)) < 0);
            assertEquals(1, calls.get());
        } finally {
            finish.countDown();
        }
    }

    private MirrorAccessProperties access() {
        var access = new MirrorAccessProperties();
        access.setApprovedOrigins(List.of(approved));
        access.setCfClearance("fixture");
        return access;
    }

    private static void reply(HttpExchange exchange, int status, String text) throws IOException {
        try (exchange) {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
