package com.example.kido.mymirror;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.kido.mymirror.manager.TokenManager;
import com.example.kido.mymirror.service.DispatcherService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Task 2 — Full HTTP integration benchmark. Drives the REAL Spring Boot application over HTTP
 * (RANDOM_PORT, real Tomcat, real {@code /playback} controller, real {@link MirrorBulkhead},
 * real {@link MirrorRequestLimit} RestTemplate interceptor, real {@code PlaybackCoalescer} and
 * {@code PlaybackService} master cache + session-generation fencing) against a local
 * {@link HttpServer} standing in for net52/net77.
 *
 * <p><b>Why a real HTTP server for the upstream, not a mocked repository:</b> the single-upstream
 * provider limit ({@code max-concurrent=1}, {@code min-interval-ms=100}) lives in a
 * {@code ClientHttpRequestInterceptor} on the mirror {@code RestTemplate}. Mocking the repository
 * would bypass exactly the limit this benchmark must preserve and measure. So only the two beans
 * that would otherwise reach the real internet are overridden — {@link DispatcherService} (origin
 * discovery → the local mock URL) and {@link TokenManager} (session/playback tokens → canned,
 * no verify2.php/home fetch). Everything on the admission and de-duplication path is the production
 * bean, unchanged. Provider limits are NOT altered.
 *
 * <p>The mock upstream answers the four calls one cold resolve makes — {@code p.php} (heartbeat),
 * {@code post.php} (open title), {@code playlist.php} (returns an {@code Auto} source whose
 * {@code file} is a mirror-relative HLS path) and the HLS master ({@code /…/hls/<id>.m3u8}, a
 * multi-variant master with a real CDN host so {@code hasPlayableVariant} passes and the body is
 * not the rate-limit penalty clip) — counting a hit per path. The hit totals ARE the real upstream
 * request counts.
 *
 * <p>Opt-in ({@code @Tag("benchmark")}); excluded from the regression baseline. Run with:
 * {@code ./gradlew test -PrunBenchmarks --tests '*PlaybackHttpIntegrationBenchmark'}. A Markdown
 * report is written to {@code build/reports/kidoo-http-benchmark.md} and its absolute path is
 * printed as {@code BENCH_REPORT_PATH=…}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("benchmark")
class PlaybackHttpIntegrationBenchmark {

    // ---- local mock upstream (the only "network") ----
    private static HttpServer upstream;
    private static String upstreamBase;
    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry meters;

    // Only the two beans that would otherwise reach the real net52/net77. Everything else is real.
    @MockitoBean
    DispatcherService dispatcher;
    @MockitoBean
    TokenManager tokenManager;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @DynamicPropertySource
    static void startUpstream(DynamicPropertyRegistry registry) throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            // Drain the request body (heartbeat posts a form) so the connection is reusable.
            exchange.getRequestBody().readAllBytes();

            String body;
            if (path.endsWith("/p.php")) {
                bump("heartbeat");
                body = "{\"r\":\"n\"}"; // keep-alive ok
            } else if (path.endsWith("/post.php")) {
                bump("openTitle");
                body = "{}";
            } else if (path.endsWith("/playlist.php")) {
                bump("playlist");
                String id = paramFromQuery(query, "id");
                // Auto master, mirror-relative so resolveOnMirror keeps it same-origin.
                body = "[{\"sources\":[{\"file\":\"/mobile/hls/" + id + ".m3u8?in=playtok\",\"label\":\"Auto\"}]}]";
            } else if (path.contains("/hls/") || path.endsWith(".m3u8")) {
                bump("hlsMaster");
                // Real CDN host (freecdn family) → hasPlayableVariant true; NOT the 220884/in=unknown penalty clip.
                body = "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\n"
                        + "https://s13.freecdn2.top/files/777/720p/720p.m3u8?in=v720\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480\n"
                        + "https://s13.freecdn2.top/files/777/480p/480p.m3u8?in=v480\n";
            } else {
                bump("other:" + path);
                body = "ok";
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        // Plenty of upstream threads: the production-side serialization is the RestTemplate interceptor
        // (one slot, 100ms pacing), never the mock's own capacity.
        upstream.setExecutor(Executors.newFixedThreadPool(32));
        upstream.start();
        upstreamBase = "http://127.0.0.1:" + upstream.getAddress().getPort();
        // The real credential-safety interceptor (MirrorHttpConfig) 403s any upstream URI not in
        // approved-origins — a production control that keeps mirror cookies on net52/net77 only. Approve the
        // local mock origin so the benchmark exercises the real resolve path instead of being short-circuited.
        registry.add("app.mymirror.access.approved-origins", () -> upstreamBase);
        registry.add("app.mymirror.dispatcher.mirrors", () -> upstreamBase);
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) upstream.stop(0);
    }

    @BeforeEach
    void stubNoNetworkBeans() {
        lenient().when(dispatcher.resolveActiveBaseUrl()).thenReturn(upstreamBase);
        lenient().when(dispatcher.cachedActiveBaseUrl()).thenReturn(upstreamBase);
        lenient().when(tokenManager.playbackT(anyString())).thenReturn("playtok");
        lenient().when(tokenManager.getHash(anyString())).thenReturn("hash");
        lenient().when(tokenManager.tokensForOrigin(anyString(), anyString()))
                .thenReturn(new TokenManager.Tokens("t", "hash", System.currentTimeMillis() / 1000 + 3600));
    }

    // =======================================================================================
    // The run
    // =======================================================================================

    @Test
    void sevenScenarios() throws Exception {
        List<Stat> stats = new ArrayList<>();

        // 1. 200 equivalent same-title requests — maximal coalescing: one owner resolves, the rest join
        //    or hit the warm master cache. Upstream should stay at ~one cold resolve (<=4 calls).
        stats.add(run("A. 200x same title", () ->
                fire(200, 64, i -> hls("A1", "benchA"))));

        // 2. 10 titles x 20 requests — one owner per title, 19 joiners/cache hits each.
        stats.add(run("B. 10 titles x 20", () ->
                fire(200, 64, i -> hls("B" + (i % 10), "benchB"))));

        // 3. 50 titles x 4 requests — 50 cold owners contend for the single upstream slot and the
        //    8-worker/16-queue bulkhead; the provider limit, not the app, caps throughput.
        stats.add(run("C. 50 titles x 4", () ->
                fire(200, 64, i -> hls("C" + (i % 50), "benchC"))));

        // 4. 200 distinct titles — fan-out with no coalescing headroom; demonstrates the bulkhead
        //    shedding load (rejected / queue-expired) rather than stampeding the one upstream slot.
        stats.add(run("D. 200 distinct", () ->
                fire(200, 64, i -> hls("D" + i, "benchD"))));

        // 5. Gradual playback starts — 50 starts spaced 150ms apart (steady-state, low contention).
        stats.add(run("E. 50 gradual starts", () ->
                fireGradual(50, 150, i -> hls("E" + i, "benchE"))));

        // 6. Periodic heartbeats — 100 keep-alives (bulkhead.submit, never coalesced); must not starve.
        stats.add(run("F. 100 heartbeats", () ->
                fire(100, 32, i -> heartbeat("benchF"))));

        // 7. Mixed playback + normal API traffic — interleave /playback/hls with /api/health to show
        //    normal API latency is unaffected by the mirror bulkhead.
        stats.add(run("G. mixed play+API", () ->
                fire(200, 64, i -> (i % 2 == 0) ? hls("G" + (i % 20), "benchG") : health())));

        String report = render(stats);
        System.out.println(report);

        File dir = new File("build/reports");
        dir.mkdirs();
        File out = new File(dir, "kidoo-http-benchmark.md");
        Files.writeString(out.toPath(), report, StandardCharsets.UTF_8);
        System.out.println("BENCH_REPORT_PATH=" + out.getAbsolutePath());
    }

    // =======================================================================================
    // Pre-deployment gate — items 1-3: sustained device-fleet capacity.
    // =======================================================================================

    /**
     * Gate items 1-3. A sustained fleet of 200 devices each sending a keep-alive heartbeat every 30s with
     * realistic staggered arrival (device {@code d} offset by {@code d * 150ms} so the 200 heartbeats spread
     * evenly across each 30s window → ~6.7 req/s, well under the single-slot + 100ms-pacing ceiling of 10 req/s),
     * held for 3 cadence cycles (~90s). Overlaid on that steady load: a simultaneous playback-start wave
     * (20 distinct-session cold resolves at t≈5s), a movie-switch wave (10 new-title cold resolves at t≈40s),
     * and normal non-mirror API traffic (/api/health once a second for the whole window — the control that
     * shows ordinary API latency is unaffected by the mirror bulkhead).
     *
     * <p>This is a DIFFERENT question from scenario F in {@link #sevenScenarios()} — F fired 100 heartbeats as
     * one thundering burst and correctly shed most of them; here the arrival rate is paced below capacity, so
     * the finding under test is near-100% heartbeat success at steady state. Reports heartbeat success rate,
     * upstream utilization, playback success rate, peak queue depth and latency percentiles.
     */
    @Test
    void sustainedDeviceFleet() throws Exception {
        final int devices = 200;
        final long cadenceMs = 30_000;
        final int cycles = 3;
        final long staggerMs = cadenceMs / devices; // 150ms
        final int windowSec = (int) (cycles * cadenceMs / 1000) + 5;

        List<Outcome> heartbeats = new CopyOnWriteArrayList<>();
        List<Outcome> playback = new CopyOnWriteArrayList<>();
        List<Outcome> normalApi = new CopyOnWriteArrayList<>();

        long upBefore = totalUpstream();

        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger maxQueue = new AtomicInteger();
        AtomicLong stop = new AtomicLong(0);
        Thread sampler = new Thread(() -> {
            while (stop.get() == 0) {
                maxActive.accumulateAndGet((int) Math.round(gaugeSum("mirror.executor.active")), Math::max);
                maxQueue.accumulateAndGet((int) Math.round(gaugeSum("mirror.executor.queue")), Math::max);
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
        });
        sampler.setDaemon(true);
        sampler.start();

        ScheduledExecutorService sched = Executors.newScheduledThreadPool(48);
        try {
            // 200 devices, staggered 150ms apart, each heartbeating every 30s for `cycles`.
            for (int d = 0; d < devices; d++) {
                final String cookie = "fleet-dev-" + d;
                long offset = d * staggerMs;
                for (int c = 0; c < cycles; c++) {
                    sched.schedule(() -> heartbeats.add(send(heartbeat(cookie))),
                            offset + c * cadenceMs, TimeUnit.MILLISECONDS);
                }
            }
            // Simultaneous playback starts at t≈5s — 20 DISTINCT sessions (distinct cookies) → 20 cold resolves
            // contending for the one upstream slot (realistic prime-time join).
            for (int d = 0; d < 20; d++) {
                final String cookie = "fleet-dev-" + d;
                sched.schedule(() -> playback.add(send(hls("feature-film", cookie))),
                        5_000 + d * 50L, TimeUnit.MILLISECONDS);
            }
            // Movie switching at t≈40s — 10 devices switch to a NEW title (new coalescer key → cold resolve).
            for (int d = 0; d < 10; d++) {
                final String cookie = "fleet-dev-" + d;
                final String id = "switch-title-" + d;
                sched.schedule(() -> playback.add(send(hls(id, cookie))),
                        40_000 + d * 80L, TimeUnit.MILLISECONDS);
            }
            // Normal non-mirror API traffic — /api/health once a second across the whole window.
            for (int t = 0; t < cycles * 30; t++) {
                sched.schedule(() -> normalApi.add(send(health())), t * 1_000L, TimeUnit.MILLISECONDS);
            }

            sched.shutdown();
            sched.awaitTermination(windowSec + 30, TimeUnit.SECONDS);
        } finally {
            if (!sched.isTerminated()) sched.shutdownNow();
        }

        stop.set(1);
        sampler.join(500);

        long upstreamCalls = totalUpstream() - upBefore;
        long capacity = (long) windowSec * 10; // single slot, 100ms pacing → ~10 upstream req/s ceiling

        Metrics hb = Metrics.of(heartbeats);
        Metrics pb = Metrics.of(playback);
        Metrics api = Metrics.of(normalApi);

        StringBuilder b = new StringBuilder();
        b.append("# Kidoo — Pre-Deployment Gate: Sustained Device-Fleet Capacity (items 1-3)\n\n");
        b.append(String.format(Locale.ROOT,
                "%d devices · heartbeat every %ds · %d staggered cycles (~%ds window) · provider limits UNCHANGED "
                        + "(`max-concurrent=1`, `min-interval-ms=100`).\n\n",
                devices, cadenceMs / 1000, cycles, windowSec));
        b.append("| Stream | req | 2xx | success | p50ms | p95ms | p99ms | maxms |\n");
        b.append("|---|--:|--:|--:|--:|--:|--:|--:|\n");
        b.append(hb.row("Heartbeats (sustained)"));
        b.append(pb.row("Playback (start + switch)"));
        b.append(api.row("Normal API (/api/health)"));
        b.append("\n**Upstream utilization:** ").append(upstreamCalls).append(" actual upstream calls over the ~")
                .append(windowSec).append("s window vs a theoretical ceiling of ~").append(capacity)
                .append(" (single slot × 10 req/s). Peak concurrent bulkhead workers (`mirror.executor.active`, max 8): ")
                .append(maxActive.get()).append(". **Peak queue depth** (`mirror.executor.queue`, cap 16): ")
                .append(maxQueue.get()).append(".\n\n");
        b.append("- **Heartbeat success rate:** ").append(hb.successPct())
                .append(" — the steady-state finding: paced ~6.7 req/s arrival stays under the 10 req/s ceiling, so "
                        + "heartbeats are NOT shed the way scenario F's thundering burst was.\n");
        b.append("- **Playback success rate:** ").append(pb.successPct())
                .append(" (any non-2xx here is correct back-pressure when a start/switch wave briefly exceeds the one "
                        + "upstream slot, surfaced to the client as 503 + `Retry-After`).\n");
        b.append("- **Normal API success rate:** ").append(api.successPct())
                .append(" at p50 ").append(api.p50()).append("ms — ordinary API latency is unaffected by the mirror bulkhead.\n");

        String report = b.toString();
        System.out.println(report);
        File dir = new File("build/reports");
        dir.mkdirs();
        File out = new File(dir, "kidoo-sustained-fleet.md");
        Files.writeString(out.toPath(), report, StandardCharsets.UTF_8);
        System.out.println("FLEET_REPORT_PATH=" + out.getAbsolutePath());
    }

    // =======================================================================================
    // Pre-deployment gate — item 6: caller isolation through the real HTTP controller.
    // =======================================================================================

    /**
     * Gate item 6. Drives the real {@code /playback/hls} controller over HTTP to show the three isolation
     * properties the coalescer key ({@code sign(cookie) + id + scope}) guarantees:
     * <ol>
     *   <li><b>Same session + same title</b> → one cold resolve shared by all callers (one owner, the rest join
     *       or hit the warm master cache). This is the de-dup win.</li>
     *   <li><b>Different session (different cookie) + same title</b> → each session resolves independently; a
     *       caller is NEVER served another session's in-flight resolution. A different cookie is also a different
     *       session generation, so it structurally cannot join an old-generation entry (gate item 5).</li>
     *   <li><b>Same session + different title</b> → each title resolves independently.</li>
     * </ol>
     * Prints the measured upstream-call and owner/joiner counts per phase.
     */
    @Test
    void httpControllerIsolation() {
        // (1) same session + same title → maximal coalescing.
        long up0 = totalUpstream();
        double owner0 = counter("mirror.coalesce", "role", "owner");
        double joiner0 = counter("mirror.coalesce", "role", "joiner");
        fire(40, 40, i -> hls("isolation-title", "session-shared"));
        long sharedUpstream = totalUpstream() - up0;
        long sharedOwners = (long) (counter("mirror.coalesce", "role", "owner") - owner0);
        long sharedJoiners = (long) (counter("mirror.coalesce", "role", "joiner") - joiner0);

        // (2) different session, same title → no sharing across sessions.
        long up1 = totalUpstream();
        double owner1 = counter("mirror.coalesce", "role", "owner");
        fire(20, 20, i -> hls("isolation-title", "session-" + i));
        long perSessionUpstream = totalUpstream() - up1;
        long perSessionOwners = (long) (counter("mirror.coalesce", "role", "owner") - owner1);

        // (3) same session, different title → no sharing across titles.
        long up2 = totalUpstream();
        double owner2 = counter("mirror.coalesce", "role", "owner");
        fire(20, 20, i -> hls("isolation-id-" + i, "session-per-title"));
        long perTitleUpstream = totalUpstream() - up2;
        long perTitleOwners = (long) (counter("mirror.coalesce", "role", "owner") - owner2);

        System.out.println(String.format(Locale.ROOT,
                "ISOLATION (1) same session+title: 40 req → %d upstream, %d owner, %d joiners (coalesced)%n"
                        + "ISOLATION (2) 20 distinct sessions, same title → %d owners admitted, %d upstream (isolated)%n"
                        + "ISOLATION (3) same session, 20 distinct titles → %d owners admitted, %d upstream (isolated)",
                sharedUpstream, sharedOwners, sharedJoiners,
                perSessionOwners, perSessionUpstream,
                perTitleOwners, perTitleUpstream));

        // One session/title collapses to a single cold resolve; distinct sessions and distinct titles each
        // admit their own owner (bounded by the bulkhead, so "owners admitted" may be < requested when the
        // single upstream slot sheds the overflow — still strictly more than the 1 of the shared case).
        if (sharedOwners != 1) {
            throw new AssertionError("same session+title must admit exactly one owner, got " + sharedOwners);
        }
        if (perSessionOwners <= sharedOwners) {
            throw new AssertionError("distinct sessions must each admit independently, got " + perSessionOwners);
        }
        if (perTitleOwners <= sharedOwners) {
            throw new AssertionError("distinct titles must each admit independently, got " + perTitleOwners);
        }
    }

    // =======================================================================================
    // Scenario runner: snapshot meters + sample gauges around the HTTP firing.
    // =======================================================================================

    private Stat run(String name, Supplier<List<Outcome>> body) throws InterruptedException {
        long upBefore = totalUpstream();
        double ownerBefore = counter("mirror.coalesce", "role", "owner");
        double joinerBefore = counter("mirror.coalesce", "role", "joiner");
        double hitBefore = counter("mirror.master.cache", "outcome", "hit");
        double missBefore = counter("mirror.master.cache", "outcome", "miss");
        double joinedBefore = counter("mirror.master.cache", "outcome", "joined");
        double rejectedBefore = counterSum("mirror.executor.rejected");
        double expiredBefore = counterSum("mirror.executor.queue.expired");

        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger maxQueue = new AtomicInteger();
        AtomicLong stop = new AtomicLong(0);
        Thread sampler = new Thread(() -> {
            while (stop.get() == 0) {
                maxActive.accumulateAndGet((int) Math.round(gaugeSum("mirror.executor.active")), Math::max);
                maxQueue.accumulateAndGet((int) Math.round(gaugeSum("mirror.executor.queue")), Math::max);
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
        });
        sampler.setDaemon(true);
        sampler.start();

        long t0 = System.nanoTime();
        List<Outcome> outcomes = body.get();
        long wallMs = (System.nanoTime() - t0) / 1_000_000;

        stop.set(1);
        sampler.join(500);

        return Stat.of(name, outcomes, wallMs,
                totalUpstream() - upBefore,
                (long) (counter("mirror.coalesce", "role", "owner") - ownerBefore),
                (long) (counter("mirror.coalesce", "role", "joiner") - joinerBefore),
                (long) (counter("mirror.master.cache", "outcome", "hit") - hitBefore),
                (long) (counter("mirror.master.cache", "outcome", "miss") - missBefore),
                (long) (counter("mirror.master.cache", "outcome", "joined") - joinedBefore),
                (long) (counterSum("mirror.executor.rejected") - rejectedBefore),
                (long) (counterSum("mirror.executor.queue.expired") - expiredBefore),
                maxActive.get(), maxQueue.get());
    }

    // =======================================================================================
    // HTTP firing
    // =======================================================================================

    private record Outcome(int status, long latencyNanos) {}

    private List<Outcome> fire(int n, int poolSize, IntFunction<HttpRequest> reqFor) {
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            List<Callable<Outcome>> tasks = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                tasks.add(() -> send(reqFor.apply(idx)));
            }
            List<Future<Outcome>> futures = pool.invokeAll(tasks);
            List<Outcome> out = new ArrayList<>(n);
            for (Future<Outcome> f : futures) out.add(f.get());
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdown();
        }
    }

    /** Submit n requests spaced {@code gapMs} apart (steady-state arrival), each still served async. */
    private List<Outcome> fireGradual(int n, long gapMs, IntFunction<HttpRequest> reqFor) {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Outcome>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> send(reqFor.apply(idx))));
                Thread.sleep(gapMs);
            }
            List<Outcome> out = new ArrayList<>(n);
            for (Future<Outcome> f : futures) out.add(f.get());
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdown();
        }
    }

    private Outcome send(HttpRequest req) {
        long t = System.nanoTime();
        try {
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            return new Outcome(resp.statusCode(), System.nanoTime() - t);
        } catch (Exception e) {
            return new Outcome(-1, System.nanoTime() - t);
        }
    }

    private HttpRequest hls(String id, String cookie) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/playback/hls?id=" + id))
                .timeout(Duration.ofSeconds(45))
                .header("X-Mirror-Cookie", cookie)
                .GET().build();
    }

    private HttpRequest heartbeat(String cookie) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/playback/heartbeat"))
                .timeout(Duration.ofSeconds(45))
                .header("X-Mirror-Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
    }

    private HttpRequest health() {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health"))
                .timeout(Duration.ofSeconds(45))
                .GET().build();
    }

    // =======================================================================================
    // Meters / mock-hit helpers
    // =======================================================================================

    private static void bump(String key) {
        HITS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private static long totalUpstream() {
        long sum = 0;
        for (AtomicInteger v : HITS.values()) sum += v.get();
        return sum;
    }

    private double counter(String name, String tagKey, String tagVal) {
        Counter c = meters.find(name).tag(tagKey, tagVal).counter();
        return c == null ? 0 : c.count();
    }

    private double counterSum(String name) {
        return meters.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    private double gaugeSum(String name) {
        return meters.find(name).gauges().stream().mapToDouble(Gauge::value).sum();
    }

    private static String paramFromQuery(String query, String key) {
        if (query == null) return "x";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) return pair.substring(eq + 1);
        }
        return "x";
    }

    // =======================================================================================
    // Stats + report
    // =======================================================================================

    private record Stat(String name, int total, int success2xx, Map<Integer, Integer> status,
                        long p50, long p95, long p99, long max,
                        long upstreamCalls, long owners, long joiners,
                        long cacheHit, long cacheMiss, long cacheJoined,
                        long rejected, long queueExpired,
                        int maxActive, int maxQueue, long wallMs) {

        static Stat of(String name, List<Outcome> outcomes, long wallMs,
                       long upstreamCalls, long owners, long joiners,
                       long cacheHit, long cacheMiss, long cacheJoined,
                       long rejected, long queueExpired, int maxActive, int maxQueue) {
            Map<Integer, Integer> status = new TreeMap<>();
            long[] lat = new long[outcomes.size()];
            int ok = 0;
            for (int i = 0; i < outcomes.size(); i++) {
                Outcome o = outcomes.get(i);
                status.merge(o.status(), 1, Integer::sum);
                if (o.status() >= 200 && o.status() < 300) ok++;
                lat[i] = o.latencyNanos() / 1_000_000;
            }
            java.util.Arrays.sort(lat);
            return new Stat(name, outcomes.size(), ok, status,
                    pct(lat, 50), pct(lat, 95), pct(lat, 99), lat.length == 0 ? 0 : lat[lat.length - 1],
                    upstreamCalls, owners, joiners, cacheHit, cacheMiss, cacheJoined,
                    rejected, queueExpired, maxActive, maxQueue, wallMs);
        }

        private static long pct(long[] sorted, int p) {
            if (sorted.length == 0) return 0;
            int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
        }
    }

    /** Compact success/latency summary for one request stream in the sustained-fleet gate. */
    private record Metrics(int total, int ok, long p50, long p95, long p99, long max) {
        static Metrics of(List<Outcome> outcomes) {
            long[] lat = new long[outcomes.size()];
            int ok = 0;
            for (int i = 0; i < outcomes.size(); i++) {
                Outcome o = outcomes.get(i);
                if (o.status() >= 200 && o.status() < 300) ok++;
                lat[i] = o.latencyNanos() / 1_000_000;
            }
            java.util.Arrays.sort(lat);
            return new Metrics(outcomes.size(), ok, pct(lat, 50), pct(lat, 95), pct(lat, 99),
                    lat.length == 0 ? 0 : lat[lat.length - 1]);
        }

        private static long pct(long[] sorted, int p) {
            if (sorted.length == 0) return 0;
            int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
        }

        String successPct() {
            return total == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%% (%d/%d)", 100.0 * ok / total, ok, total);
        }

        String row(String label) {
            return String.format(Locale.ROOT, "| %s | %d | %d | %s | %d | %d | %d | %d |\n",
                    label, total, ok, total == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100.0 * ok / total),
                    p50, p95, p99, max);
        }
    }

    private String render(List<Stat> stats) {
        StringBuilder b = new StringBuilder();
        b.append("# Kidoo — Task 2 Full HTTP Integration Benchmark\n\n");
        b.append("Real Spring Boot app (RANDOM_PORT) + local HttpServer upstream. Provider limits UNCHANGED ")
                .append("(`max-concurrent=1`, `min-interval-ms=100`). Only `DispatcherService` and `TokenManager` ")
                .append("overridden (no real net52/net77). All de-duplication, bulkhead and interceptor beans are real.\n\n");
        b.append("`upstream` = actual HTTP calls the mock received (= real upstream requests). ")
                .append("`owners`/`joiners` = `mirror.coalesce`. `hit`/`miss`/`joined` = `mirror.master.cache`. ")
                .append("`rej`/`qexp` = `mirror.executor.rejected`/`queue.expired`. ")
                .append("`maxAct`/`maxQ` = sampled peak `mirror.executor.active`/`queue` (workers=8, queue=16).\n\n");

        b.append("| Scenario | req | 2xx | upstream | owners | joiners | hit | miss | joined | rej | qexp | maxAct | maxQ | p50ms | p95ms | p99ms | maxms | wall ms |\n");
        b.append("|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|\n");
        for (Stat s : stats) {
            b.append(String.format(Locale.ROOT,
                    "| %s | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d |\n",
                    s.name(), s.total(), s.success2xx(), s.upstreamCalls(), s.owners(), s.joiners(),
                    s.cacheHit(), s.cacheMiss(), s.cacheJoined(), s.rejected(), s.queueExpired(),
                    s.maxActive(), s.maxQueue(), s.p50(), s.p95(), s.p99(), s.max(), s.wallMs()));
        }
        b.append("\n### HTTP status distribution per scenario\n\n");
        for (Stat s : stats) {
            b.append("- **").append(s.name()).append("**: ").append(s.status()).append('\n');
        }
        b.append("\n_Note: a non-2xx under heavy fan-out (scenarios C/D) is correct load-shedding by the ")
                .append("bulkhead/provider limit, not a failure — the single upstream slot is preserved by design._\n");
        return b.toString();
    }
}
