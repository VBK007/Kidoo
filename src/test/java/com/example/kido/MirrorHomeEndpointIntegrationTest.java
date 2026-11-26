package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.kido.media.dto.HomeDtos.MirrorRailDto;
import com.example.kido.media.dto.HomeDtos.MirrorTileDto;
import com.example.kido.mymirror.MirrorBulkhead;
import com.example.kido.mymirror.catalogue.MirrorCatalogueProperties;
import com.example.kido.mymirror.service.MirrorHomeService;
import com.example.kido.mymirror.service.MirrorHomeService.RailResult;

/**
 * Endpoint-level coverage for {@code /api/media/home} — the whole request path (security, profile
 * resolution, {@link com.example.kido.media.home.HomeController}, the mirror bulkhead and the
 * cache-first short-circuit), not just {@code MirrorCatalogueStore.read()}.
 *
 * <p>{@link MirrorHomeService} is mocked so the two read surfaces it exposes — {@code warmRails}
 * (cache-only) and {@code railWithStatus} (live/upstream) — can be driven independently and their
 * invocation counts asserted. That makes "upstream request count" observable at the controller
 * boundary: a genuine cache-first hit must invoke {@code railWithStatus} <b>zero</b> times. The
 * real-store / real-DB counterpart (restart persistence, DB-statement counts, zero-upstream on a
 * fresh L2 hit through live wiring) lives in {@code MirrorHomeCacheFirstStackTest} and the store
 * matrix test; here the upstream is the mock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MirrorHomeEndpointIntegrationTest {

    @LocalServerPort
    int port;

    @MockitoBean
    MirrorHomeService mirrorHome;

    @Autowired
    MirrorCatalogueProperties catalogueProps;

    @Autowired
    MirrorBulkhead bulkhead;

    private final HttpClient http = HttpClient.newHttpClient();

    private static final MirrorRailDto RAIL = new MirrorRailDto("mirror", "From the mirror",
            List.of(new MirrorTileDto("111", "Alpha", "https://img.example/111.jpg", null)));

    /** Cache-first is a global, default-off switch; every test that turns it on resets it here so the
     *  historical-path tests in this class keep running against the byte-for-byte original behaviour. */
    @AfterEach
    void resetCacheFirst() {
        catalogueProps.getCacheFirst().setEnabled(false);
    }

    // ---------------------------------------------------------------------
    // Historical path (cache-first OFF) — unchanged behaviour.
    // ---------------------------------------------------------------------

    @Test
    void aParentWithAMirrorSessionGetsBothTheLibraryAndTheMirror() throws Exception {
        when(mirrorHome.railWithStatus("sess=1", null)).thenReturn(new RailResult(List.of(RAIL), "available"));
        String[] account = account("PARENT");

        HttpResponse<String> home = home(account, "sess=1");

        assertEquals(200, home.statusCode(), home.body());
        assertTrue(home.body().contains("\"rails\""), home.body());
        assertTrue(home.body().contains("\"mirror\":[{\"key\":\"mirror\""), home.body());
        assertTrue(home.body().contains("\"id\":\"111\""), home.body());
    }

    @Test
    void withoutAMirrorSessionTheHomeScreenIsUnchanged() throws Exception {
        when(mirrorHome.railWithStatus(null, null)).thenReturn(new RailResult(List.of(), "missing"));

        HttpResponse<String> home = home(account("PARENT"), null);

        assertEquals(200, home.statusCode(), home.body());
        assertTrue(home.body().contains("\"mirror\":null"), home.body());
    }

    @Test
    void aChildAccountAlsoSeesTheMirror() throws Exception {
        // The mirror catalogue has no age filtering; this build composes the rail for children too
        // (a deliberate choice recorded on HomeController#home), so the gate is signed-in, not parent.
        when(mirrorHome.railWithStatus("sess=1", null)).thenReturn(new RailResult(List.of(RAIL), "available"));

        HttpResponse<String> home = home(account("CHILD"), "sess=1");

        assertEquals(200, home.statusCode(), home.body());
        assertTrue(home.body().contains("\"id\":\"111\""), home.body());
    }

    @Test
    void withCacheFirstOffTheWarmReadIsNeverConsulted() throws Exception {
        // The flag-off path must be byte-for-byte the historical behaviour: the controller never takes
        // the cache-only warm read at all, it only submits the live refresh to the bulkhead.
        when(mirrorHome.railWithStatus("sess=1", null)).thenReturn(new RailResult(List.of(RAIL), "available"));

        HttpResponse<String> home = home(account("PARENT"), "sess=1");

        assertEquals(200, home.statusCode(), home.body());
        verify(mirrorHome, never()).warmRails(any(), any());
        verify(mirrorHome, times(1)).railWithStatus("sess=1", null);
    }

    // ---------------------------------------------------------------------
    // Cache-first ON — the #1 guarantee: a fresh warm catalogue serves immediately
    // and submits ZERO upstream catalogue requests.
    // ---------------------------------------------------------------------

    @Test
    void aFreshWarmCatalogueIsServedImmediatelyWithZeroUpstreamRequests() throws Exception {
        catalogueProps.getCacheFirst().setEnabled(true);
        // nf: a fresh ("cached") warm catalogue of three rails.
        when(mirrorHome.warmRails("sess=1", "nf")).thenReturn(new RailResult(sampleRails(), "cached"));

        HttpResponse<String> home = home(account("PARENT"), "sess=1", "nf");

        assertEquals(200, home.statusCode(), home.body());
        assertEquals("cached", home.headers().firstValue("X-Mirror-Catalogue-State").orElse(null));
        // The whole point of cache-first: the live/upstream read is not invoked at all on a fresh hit.
        verify(mirrorHome, never()).railWithStatus(any(), any());
        // Category count (three rails) and title count (2 + 1 + 1 = 4 tiles) survive to the response body.
        assertEquals(3, countMirrorRails(home.body()), home.body());
        assertEquals(4, countMirrorTiles(home.body()), home.body());
    }

    @Test
    void aFreshHitBypassesTheBulkheadEvenWhenTheExecutorIsSaturated() throws Exception {
        catalogueProps.getCacheFirst().setEnabled(true);
        when(mirrorHome.warmRails("sess=1", "nf")).thenReturn(new RailResult(sampleRails(), "cached"));

        // Occupy the mirror executor with blocking work. The fresh-hit path never reaches the bulkhead,
        // so the cached catalogue must still answer promptly while every worker is busy.
        CountDownLatch release = new CountDownLatch(1);
        List<CompletableFuture<?>> blockers = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            try {
                blockers.add(bulkhead.submitFuture(() -> {
                    release.await(10, TimeUnit.SECONDS);
                    return null;
                }));
            } catch (RuntimeException rejected) {
                break; // queue full — enough workers are now blocked for the test's purpose
            }
        }
        try {
            long start = System.nanoTime();
            HttpResponse<String> home = home(account("PARENT"), "sess=1", "nf");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(200, home.statusCode(), home.body());
            assertEquals("cached", home.headers().firstValue("X-Mirror-Catalogue-State").orElse(null));
            verify(mirrorHome, never()).railWithStatus(any(), any());
            // Comfortably under the 8s bulkhead budget — it never waited on a worker slot.
            assertTrue(elapsedMs < 4000, "fresh hit under saturation took " + elapsedMs + "ms");
        } finally {
            release.countDown();
            for (CompletableFuture<?> b : blockers) {
                try { b.get(10, TimeUnit.SECONDS); } catch (Exception ignored) { /* draining */ }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Cache-first ON — stale / partial / cold fall through the bounded bulkhead
    // to the live refresh; the warm snapshot is only the saturation fallback.
    // ---------------------------------------------------------------------

    @Test
    void aStaleWarmCatalogueRefreshesThroughTheBulkhead() throws Exception {
        catalogueProps.getCacheFirst().setEnabled(true);
        // pv: the warm read is stale, so the controller must prefer a live refresh.
        when(mirrorHome.warmRails("sess=1", "pv")).thenReturn(new RailResult(List.of(RAIL), "stale"));
        when(mirrorHome.railWithStatus("sess=1", "pv"))
                .thenReturn(new RailResult(List.of(freshRail()), "available"));

        HttpResponse<String> home = home(account("PARENT"), "sess=1", "pv");

        assertEquals(200, home.statusCode(), home.body());
        // The live refresh returned before the budget, so its result (not the stale fallback) is served.
        assertEquals("available", home.headers().firstValue("X-Mirror-Catalogue-State").orElse(null));
        assertTrue(home.body().contains("\"id\":\"999\""), home.body());
        verify(mirrorHome, times(1)).railWithStatus("sess=1", "pv");
    }

    @Test
    void aHungLiveRefreshIsBoundedByTheDeadlineRatherThanWaitingForever() throws Exception {
        catalogueProps.getCacheFirst().setEnabled(true);
        // dp (Hotstar): warm read is stale and a live refresh IS admitted (a worker was free) but then
        // hangs past the bounded budget. The bulkhead's stale fallback covers executor *saturation*
        // (no free worker); an admitted-then-overrunning task instead hits the bounded request deadline
        // and returns 504. This pins the current contract: refresh happens only through the bounded
        // mechanism and never waits unbounded.
        //
        // NOTE (reported to the maintainer, not silently changed): when a usable stale/partial snapshot
        // is in hand, arguably #1 ("serve permitted stale snapshots"; "cached Home available
        // independently of executor saturation") would prefer serving that stale snapshot over a 504 on
        // a hung refresh. Making that change means having submitOptional deliver the fallback on the
        // deadline/error path too — a controller+bulkhead change held for explicit approval.
        when(mirrorHome.warmRails("sess=1", "dp")).thenReturn(new RailResult(List.of(RAIL), "stale"));
        when(mirrorHome.railWithStatus("sess=1", "dp")).thenAnswer(inv -> {
            Thread.sleep(30_000); // never returns within the budget
            return new RailResult(List.of(freshRail()), "available");
        });

        HttpResponse<String> home = home(account("PARENT"), "sess=1", "dp");

        assertEquals(504, home.statusCode(), home.body());
    }

    @Test
    void aColdCacheGoesStraightToTheLiveRefresh() throws Exception {
        catalogueProps.getCacheFirst().setEnabled(true);
        // Nothing warm on hand ("unavailable"): the controller must refresh live through the bulkhead.
        when(mirrorHome.warmRails("sess=1", "nf")).thenReturn(new RailResult(List.of(), "unavailable"));
        when(mirrorHome.railWithStatus("sess=1", "nf"))
                .thenReturn(new RailResult(List.of(freshRail()), "available"));

        HttpResponse<String> home = home(account("PARENT"), "sess=1", "nf");

        assertEquals(200, home.statusCode(), home.body());
        assertEquals("available", home.headers().firstValue("X-Mirror-Catalogue-State").orElse(null));
        assertTrue(home.body().contains("\"id\":\"999\""), home.body());
        verify(mirrorHome, times(1)).railWithStatus("sess=1", "nf");
    }

    // ---------------------------------------------------------------------
    // Concurrency and latency benchmarks at the endpoint.
    // ---------------------------------------------------------------------

    @Test
    void concurrentHomeRequestsAllServeTheFreshCatalogue() throws Exception {
        catalogueProps.getCacheFirst().setEnabled(true);
        when(mirrorHome.warmRails("sess=1", "nf")).thenReturn(new RailResult(sampleRails(), "cached"));
        String[] account = account("PARENT");

        int clients = 24;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try { return home(account, "sess=1", "nf"); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }, pool));
            }
            for (CompletableFuture<HttpResponse<String>> call : calls) {
                HttpResponse<String> home = call.get(30, TimeUnit.SECONDS);
                assertEquals(200, home.statusCode(), home.body());
                assertEquals("cached", home.headers().firstValue("X-Mirror-Catalogue-State").orElse(null));
            }
        } finally {
            pool.shutdownNow();
        }
        verify(mirrorHome, never()).railWithStatus(any(), any());
    }

    @Test
    void freshHitLatencyPercentilesAreReported() throws Exception {
        catalogueProps.getCacheFirst().setEnabled(true);
        when(mirrorHome.warmRails("sess=1", "nf")).thenReturn(new RailResult(sampleRails(), "cached"));
        String[] account = account("PARENT");

        int iterations = 120;
        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            HttpResponse<String> home = home(account, "sess=1", "nf");
            samples[i] = (System.nanoTime() - start) / 1_000_000;
            assertEquals(200, home.statusCode(), home.body());
            assertEquals("cached", home.headers().firstValue("X-Mirror-Catalogue-State").orElse(null));
        }
        java.util.Arrays.sort(samples);
        long p50 = samples[(int) (iterations * 0.50)];
        long p95 = samples[(int) (iterations * 0.95)];
        long p99 = samples[(int) (iterations * 0.99)];
        System.out.printf("[cache-first endpoint] fresh-hit latency over %d reqs: p50=%dms p95=%dms p99=%dms max=%dms%n",
                iterations, p50, p95, p99, samples[iterations - 1]);
        // A cache-first hit does no upstream and no bulkhead work; p99 must stay well under the budget.
        assertTrue(p99 < 2000, "p99 fresh-hit latency " + p99 + "ms");
    }

    // ---- helpers ----

    /** Three rails, four tiles total — a shape for asserting category and title counts. */
    private static List<MirrorRailDto> sampleRails() {
        return List.of(
                new MirrorRailDto("top10", "Top 10", List.of(
                        new MirrorTileDto("201", "One", "https://imgcdn.kim/nf/v/201.jpg", null),
                        new MirrorTileDto("202", "Two", "https://imgcdn.kim/nf/v/202.jpg", null))),
                new MirrorRailDto("trending", "Trending", List.of(
                        new MirrorTileDto("203", "Three", "https://imgcdn.kim/nf/v/203.jpg", null))),
                new MirrorRailDto("because", "Because you watched", List.of(
                        new MirrorTileDto("204", "Four", "https://imgcdn.kim/nf/v/204.jpg", null))));
    }

    private static MirrorRailDto freshRail() {
        return new MirrorRailDto("live", "Fresh from upstream",
                List.of(new MirrorTileDto("999", "Live", "https://imgcdn.kim/nf/v/999.jpg", null)));
    }

    private HttpResponse<String> home(String[] account, String mirrorCookie) throws Exception {
        return home(account, mirrorCookie, null);
    }

    private HttpResponse<String> home(String[] account, String mirrorCookie, String ott) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/media/home"))
                .header("Authorization", "Bearer " + account[0]);
        if (account[1] != null) req.header("X-Profile-Id", account[1]);
        if (mirrorCookie != null) req.header("X-Mirror-Cookie", mirrorCookie);
        if (ott != null) req.header("X-Mirror-Ott", ott);
        return http.send(req.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Bearer token and profile id for a fresh account of the given role. */
    private String[] account(String role) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = post("/api/auth/register", """
                {"username":"mh_%s","email":"mh_%s@example.com","password":"pw123456",
                 "displayName":"Mirror Home","role":"%s"}
                """.formatted(unique, unique, role), null);
        assertEquals(201, registered.statusCode(), registered.body());
        String bearer = extract(registered.body(), "token");
        HttpResponse<String> profile = post("/api/profiles", "{\"name\":\"Tester\",\"ageMode\":\"OLDER\"}", bearer);
        return new String[] {bearer, profile.statusCode() == 201 ? extract(profile.body(), "id") : null};
    }

    private HttpResponse<String> post(String path, String json, String bearer) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (bearer != null) req.header("Authorization", "Bearer " + bearer);
        return http.send(req.POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String extract(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Count non-overlapping occurrences of a literal needle. */
    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /** Number of rails inside the mirror block — each rail object carries one {@code "key":}. */
    private static int countMirrorRails(String body) {
        int mirror = body.indexOf("\"mirror\":[");
        if (mirror < 0) {
            return 0;
        }
        return count(body.substring(mirror), "\"key\":\"");
    }

    /** Number of tiles inside the mirror block — each mirror tile object carries one {@code "id":}.
     *  Scoped to the mirror block so the library/continue-watching items (which also carry ids, and
     *  serialise before {@code mirror}) are never counted. */
    private static int countMirrorTiles(String body) {
        int mirror = body.indexOf("\"mirror\":[");
        if (mirror < 0) {
            return 0;
        }
        return count(body.substring(mirror), "\"id\":\"");
    }
}
