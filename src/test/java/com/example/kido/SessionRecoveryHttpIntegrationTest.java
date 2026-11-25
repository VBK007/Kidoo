package com.example.kido;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.kido.mymirror.SessionRecoveryCoordinator;
import com.example.kido.mymirror.service.DispatcherService;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "app.mymirror.recovery.enabled=true",
                "app.mymirror.recovery.node=/bin/sh", "app.mymirror.recovery.acquisition-timeout=45s",
                "app.mymirror.recovery.expiry-status=401", "app.mymirror.recovery.expiry-header=X-Fixture-Session",
                "app.mymirror.recovery.expiry-value=expired", "app.mymirror.recovery.validation-header=X-Fixture-Valid",
                "app.mymirror.recovery.validation-value=yes", "app.mymirror.session.keep-alive-enabled=false",
                "app.admin.api-key=recovery-test-key"})
class SessionRecoveryHttpIntegrationTest {
    private static final Path DIRECTORY;
    private static final HttpServer MOCK;
    private static final String ORIGIN;
    private static final AtomicBoolean EXPIRED = new AtomicBoolean();
    private static final AtomicInteger EXPIRED_RESPONSES = new AtomicInteger();
    private static final AtomicInteger VALIDATIONS = new AtomicInteger();

    static {
        try {
            var directoryMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
            DIRECTORY = Files.createTempDirectory("mirror-recovery-http-", directoryMode);
            Path staging = Files.createDirectory(DIRECTORY.resolve("staging"), directoryMode);
            Files.createDirectory(staging.resolve("operator"), directoryMode);
            Path active = Files.createFile(DIRECTORY.resolve("session.txt"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(active, "t_hash_t=fixture-old\n");
            Files.writeString(DIRECTORY.resolve("acquire.sh"), """
                    printf 'launch\\n' >> launches
                    while [ ! -f proceed ]; do /bin/sleep 0.05; done
                    umask 077
                    printf 't_hash_t=fixture-new\\n' > "$1"
                    """);
            MOCK = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
            ORIGIN = "http://127.0.0.1:" + MOCK.getAddress().getPort();
            MOCK.createContext("/mobile/verify2.php", exchange -> {
                try (exchange) {
                    if (EXPIRED.get() && "t_hash_t=fixture-old".equals(exchange.getRequestHeaders().getFirst("Cookie"))) {
                        EXPIRED_RESPONSES.incrementAndGet();
                        exchange.getResponseHeaders().set("X-Fixture-Session", "expired");
                        exchange.sendResponseHeaders(401, -1);
                    } else {
                        byte[] response = (exchange.getRequestMethod().equals("POST") ? "{\"c\":\"y\"}" :
                                "<html><body></body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                    }
                }
            });
            MOCK.createContext("/mobile/p.php", exchange -> {
                try (exchange) { exchange.sendResponseHeaders(204, -1); }
            });
            MOCK.createContext("/validate", exchange -> {
                try (exchange) {
                    VALIDATIONS.incrementAndGet();
                    if ("t_hash_t=fixture-new".equals(exchange.getRequestHeaders().getFirst("Cookie"))) {
                        exchange.getResponseHeaders().set("X-Fixture-Valid", "yes");
                    }
                    exchange.sendResponseHeaders(204, -1);
                }
            });
            MOCK.createContext("/mobile/post.php", exchange -> {
                try (exchange) { exchange.sendResponseHeaders(204, -1); }
            });
            MOCK.createContext("/mobile/playlist.php", exchange -> fixtureResponse(exchange,
                    "[{\"sources\":[{\"file\":\"/mobile/hls/fixture.m3u8?in=local-fixture\",\"label\":\"Auto\"}]}]"));
            MOCK.createContext("/mobile/hls/fixture.m3u8", exchange -> fixtureResponse(exchange,
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n710/fixture.m3u8\n"));
            MOCK.start();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static void fixtureResponse(com.sun.net.httpserver.HttpExchange exchange, String value)
            throws java.io.IOException {
        try (exchange) {
            byte[] response = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        properties.add("app.mymirror.access.approved-origins", () -> ORIGIN);
        properties.add("app.mymirror.session.session-file", () -> DIRECTORY.resolve("session.txt").toString());
        properties.add("app.mymirror.recovery.working-directory", DIRECTORY::toString);
        properties.add("app.mymirror.recovery.script", () -> DIRECTORY.resolve("acquire.sh").toString());
        properties.add("app.mymirror.recovery.staging-directory", () -> DIRECTORY.resolve("staging").toString());
        properties.add("app.mymirror.recovery.metadata-path", () -> DIRECTORY.resolve("recovery.json").toString());
        properties.add("app.mymirror.recovery.expiry-endpoint", () -> ORIGIN + "/mobile/verify2.php");
        properties.add("app.mymirror.recovery.validation-endpoint", () -> ORIGIN + "/validate");
    }

    @LocalServerPort int port;
    @MockitoBean DispatcherService dispatcher;
    @Autowired SessionRecoveryCoordinator recovery;
    @Autowired com.example.kido.mymirror.MirrorStreamSigner signer;
    @Autowired com.example.kido.mymirror.manager.TokenManager tokens;

    @AfterAll
    static void stopMock() {
        MOCK.stop(0);
    }

    @Test
    void burstLaunchesOneRecoveryAndCoreApiRespondsWhileTheOperatorIsPending() throws Exception {
        when(dispatcher.resolveActiveBaseUrl()).thenReturn(ORIGIN);
        when(dispatcher.cachedActiveBaseUrl()).thenReturn(ORIGIN);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var registration = client.send(request("/api/auth/register")
                .header("Content-Type", "application/json")
                .header("X-Admin-Key", "recovery-test-key")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"username":"recovery_fixture","email":"recovery_fixture@example.com",
                         "password":"pw123456","displayName":"Recovery","role":"PARENT"}
                        """)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, registration.statusCode());
        String bearer = JsonMapper.builder().build().readTree(registration.body()).get("token").asString();
        String attempt = java.util.UUID.randomUUID().toString();
        var master = request("/playback/hls?id=fixture").header("X-Mirror-Cookie", "t_hash_t=fixture-old")
                .header("X-Playback-Attempt-Id", attempt).GET().build();
        var first = client.send(master, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.statusCode());
        assertEquals(attempt, first.headers().firstValue("X-Playback-Attempt-Id").orElseThrow());
        var link = java.util.regex.Pattern.compile("/playback/variant[?]u=([^&\\s]+)&s=([^\\s]+)")
                .matcher(first.body());
        assertTrue(link.find());
        String payload = signer.verify(link.group(1), link.group(2));
        assertNotNull(payload);
        EXPIRED.set(true);
        // Simulate the local token TTL passing while the HLS cache still contains the signed master.
        ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(tokens, "byCookie")).clear();
        var heartbeat = request("/playback/heartbeat").POST(HttpRequest.BodyPublishers.noBody()).build();
        var start = new CountDownLatch(1);
        record Sample(int status, double elapsedMs) {}
        double[] timings = new double[200];
        double coreMs;
        try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            var wave = java.util.stream.IntStream.range(0, 200).mapToObj(i -> clients.submit(() -> {
                start.await();
                long began = System.nanoTime();
                int status = client.send(heartbeat, HttpResponse.BodyHandlers.discarding()).statusCode();
                return new Sample(status, (System.nanoTime() - began) / 1e6);
            })).toList();
            start.countDown();
            await(SessionRecoveryCoordinator.State.ACQUIRING);
            assertEquals(503, client.send(master, HttpResponse.BodyHandlers.discarding()).statusCode());
            long coreStarted = System.nanoTime();
            var core = client.send(request("/api/auth/me").timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.discarding());
            coreMs = (System.nanoTime() - coreStarted) / 1e6;
            assertEquals(200, core.statusCode());
            var denied = client.send(request("/api/admin/mirror/recovery").GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(403, denied.statusCode());
            var status = client.send(request("/api/admin/mirror/recovery").header("Authorization", "Bearer " + bearer)
                    .header("X-Admin-Key", "recovery-test-key").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, status.statusCode());
            assertTrue(status.body().contains("ACQUIRING"));
            assertFalse(status.body().contains("fixture-old"));
            var overview = client.send(request("/api/admin/mirror/recovery/overview")
                    .header("Authorization", "Bearer " + bearer).header("X-Admin-Key", "recovery-test-key")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, overview.statusCode());
            var metrics = JsonMapper.builder().build().readTree(overview.body());
            assertEquals("REVOKED", metrics.path("sessionState").asString());
            assertTrue(metrics.path("executors").has("foreground"));
            assertFalse(overview.body().contains("fixture-old"));
            for (int i = 0; i < timings.length; i++) {
                Sample completed = wave.get(i).get(12, TimeUnit.SECONDS);
                assertEquals(503, completed.status());
                timings[i] = completed.elapsedMs();
            }
        } finally {
            Files.createFile(DIRECTORY.resolve("proceed"));
        }
        await(SessionRecoveryCoordinator.State.SUCCEEDED);
        java.util.Arrays.sort(timings);
        var outcome = recovery.status();
        System.out.printf(java.util.Locale.ROOT,
                "RECOVERY_FIXTURE attempts=200 revokedOrBusy=200 avgMs=%.1f p95Ms=%.1f p99Ms=%.1f "
                + "coreMs=%.1f expiryCalls=%d validationCalls=%d recoveryMs=%d%n",
                java.util.Arrays.stream(timings).average().orElseThrow(), timings[189], timings[197], coreMs,
                EXPIRED_RESPONSES.get(), VALIDATIONS.get(),
                Duration.between(outcome.startedAt(), outcome.lastSuccessAt()).toMillis());
        assertEquals(1, VALIDATIONS.get());
        assertEquals(1, EXPIRED_RESPONSES.get());
        assertEquals(1, Files.readAllLines(DIRECTORY.resolve("launches")).size());
        assertEquals(200, client.send(heartbeat, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(503, client.send(master, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(payload, signer.verify(link.group(1), link.group(2)));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(12));
    }

    private void await(SessionRecoveryCoordinator.State expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (recovery.status().state() != expected && System.nanoTime() < deadline) Thread.sleep(25);
        assertEquals(expected, recovery.status().state(), () -> recovery.status().outcome());
    }
}
