package com.example.kido.mymirror.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import com.example.kido.mymirror.MirrorBulkhead;
import com.example.kido.mymirror.MirrorRequestLimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local, mock-upstream benchmark for the pre-admission HLS-master coalescer.
 *
 * <p>This does NOT touch net52/net77 or stream any video. It wires the <em>real</em>
 * {@link MirrorBulkhead} and {@link PlaybackCoalescer} to a mock resolver that reproduces the two-gate
 * admission funnel the real path has:
 * <ol>
 *   <li><b>Gate 1 — the bulkhead:</b> 8 worker threads + a 16-slot queue (24 hard admissions; the rest
 *       are rejected {@code Busy} instantly), with a 2s queue timeout.</li>
 *   <li><b>Gate 2 — the single upstream slot:</b> a {@code Semaphore(1)} plus a 100ms minimum pacing
 *       interval, so upstream resolves run one at a time — the provider concurrency this task must not
 *       widen.</li>
 * </ol>
 * The mock also reproduces the in-worker single-flight ({@link PlaybackService#hlsMaster}'s master cache):
 * admitted callers for the same title share one upstream resolve, and once a title resolves its result is
 * cached, so later callers take a local fast-path (mirroring the controller's {@code cachedHlsMaster}
 * check) without admission.
 *
 * <p>Absolute latencies are scaled down for test speed (a ~300ms mock resolve stands in for a ~1–2s cold
 * master); the <em>ratios</em> — 24-wide admission vs a one-at-a-time upstream — are what the funnel
 * turns on, and they are preserved. The report prints before (coalescer off) vs after (on) for three
 * arrival patterns. Tagged {@code benchmark} so it is opt-in, not part of the default unit run.
 */
@Tag("benchmark")
class PlaybackCoalesceBenchmark {

    private static final int DEVICES = 200;
    private static final int WORKERS = 8;
    private static final int QUEUE = 16;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration QUEUE_TIMEOUT = Duration.ofSeconds(2);
    private static final long RESOLVE_MS = 300;   // mock cold-master upstream latency
    private static final long PACING_MS = 100;    // min interval between upstream acquisitions

    private enum Outcome { SUCCESS, BUSY, DEADLINE, OTHER }

    private record Sample(Outcome outcome, long latencyMs) {}

    /** The mock upstream: one slot, paced, with per-title in-worker single-flight and a result cache. */
    private static final class MockMirror {
        private final ConcurrentHashMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();
        private final Semaphore upstreamSlot = new Semaphore(1, true);
        private final AtomicInteger upstreamCalls = new AtomicInteger();
        private long lastAcquireMs = 0;

        /** Controller-style local fast-path: a freshly resolved title serves without admission. */
        String cached(String title) {
            CompletableFuture<String> f = inflight.get(title);
            return f != null && f.isDone() && !f.isCompletedExceptionally() ? f.getNow(null) : null;
        }

        /** The admitted resolve: shares an in-flight/cached result per title, else does one paced upstream call. */
        ResponseEntity<String> resolve(String title, long deadlineNanos) {
            AtomicReference<CompletableFuture<String>> owned = new AtomicReference<>();
            CompletableFuture<String> future = inflight.compute(title, (k, existing) -> {
                if (existing != null && (!existing.isDone() || !existing.isCompletedExceptionally())) return existing;
                CompletableFuture<String> fresh = new CompletableFuture<>();
                owned.set(fresh);
                return fresh;
            });
            if (owned.get() == future) {
                try {
                    callUpstream(deadlineNanos);
                    future.complete("#EXTM3U\n# " + title);
                } catch (RuntimeException ex) {
                    future.completeExceptionally(ex);
                    inflight.remove(title, future);
                    throw ex;
                }
            }
            try {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) throw new MirrorRequestLimit.DeadlineExceeded();
                return ResponseEntity.ok(future.get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException ex) {
                throw new MirrorRequestLimit.DeadlineExceeded();
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof RuntimeException re) throw re;
                throw new IllegalStateException(ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MirrorRequestLimit.DeadlineExceeded();
            }
        }

        private void callUpstream(long deadlineNanos) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) throw new MirrorRequestLimit.DeadlineExceeded();
            boolean acquired;
            try {
                acquired = upstreamSlot.tryAcquire(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MirrorRequestLimit.DeadlineExceeded();
            }
            if (!acquired) throw new MirrorRequestLimit.DeadlineExceeded();
            try {
                synchronized (this) {
                    long now = System.currentTimeMillis();
                    long wait = lastAcquireMs + PACING_MS - now;
                    if (wait > 0) sleep(wait);
                    lastAcquireMs = System.currentTimeMillis();
                }
                upstreamCalls.incrementAndGet();
                sleep(RESOLVE_MS);
            } finally {
                upstreamSlot.release();
            }
        }

        private static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MirrorRequestLimit.DeadlineExceeded();
            }
        }
    }

    @Test
    void reportsColdStartSuccessBeforeAndAfterCoalescing() throws Exception {
        System.out.println();
        System.out.println("=== HLS master pre-admission coalescing — local mock benchmark ===");
        System.out.printf("devices=%d  workers=%d  queue=%d  timeout=%s  queue-timeout=%s  upstream=1 slot @ %dms pace + %dms resolve%n",
                DEVICES, WORKERS, QUEUE, TIMEOUT, QUEUE_TIMEOUT, PACING_MS, RESOLVE_MS);
        System.out.println();
        System.out.printf("%-34s %-6s %7s %6s %8s %7s %7s %8s%n",
                "scenario", "coal.", "success", "busy", "deadline", "p50ms", "p95ms", "upstream");

        // Same popular title, all 200 devices start within the same instant (the thundering herd).
        run("same title, simultaneous", false, true, 0);
        run("same title, simultaneous", true, true, 0);

        // Same title, but devices trickle in 5ms apart (gradual onboarding).
        run("same title, gradual 5ms", false, true, 5);
        run("same title, gradual 5ms", true, true, 5);

        // 200 distinct titles at once — nothing to coalesce; this is the upstream-bound ceiling, and it
        // must be ~identical with and without the coalescer (coalescing never widens upstream capacity).
        run("distinct titles, simultaneous", false, false, 0);
        run("distinct titles, simultaneous", true, false, 0);

        System.out.println();
        System.out.println("Note: a mock resolve stands in for a real cold master; no upstream or video is touched.");
        System.out.println("Coalescing lifts the SAME-title thundering herd. For DISTINCT titles the one-at-a-time");
        System.out.println("upstream slot is the ceiling for both — by design; it is not widened.");
    }

    private void run(String label, boolean coalesce, boolean sameTitle, long staggerMs) throws Exception {
        var metrics = new SimpleMeterRegistry();
        var bulkhead = new MirrorBulkhead(WORKERS, QUEUE, TIMEOUT, QUEUE_TIMEOUT, metrics, new MirrorRequestLimit());
        var coalescer = new PlaybackCoalescer(coalesce, metrics);
        var mirror = new MockMirror();
        long budgetMs = TIMEOUT.toMillis();

        ExecutorService pool = Executors.newFixedThreadPool(DEVICES);
        CountDownLatch ready = new CountDownLatch(DEVICES);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Sample>> samples = new ArrayList<>();

        for (int i = 0; i < DEVICES; i++) {
            final String title = sameTitle ? "popular" : "title-" + i;
            final long delay = staggerMs * i;
            var done = new CompletableFuture<Sample>();
            samples.add(done);
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    if (delay > 0) Thread.sleep(delay);
                    long start = System.nanoTime();
                    Outcome outcome = oneRequest(bulkhead, coalescer, mirror, coalesce, title, budgetMs, start);
                    done.complete(new Sample(outcome, (System.nanoTime() - start) / 1_000_000));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    done.complete(new Sample(Outcome.OTHER, 0));
                }
            });
        }

        ready.await();
        go.countDown();
        List<Sample> results = new ArrayList<>();
        for (var s : samples) results.add(s.get(budgetMs + 10_000, TimeUnit.MILLISECONDS));
        pool.shutdownNow();

        long success = results.stream().filter(s -> s.outcome() == Outcome.SUCCESS).count();
        long busy = results.stream().filter(s -> s.outcome() == Outcome.BUSY).count();
        long deadline = results.stream().filter(s -> s.outcome() == Outcome.DEADLINE).count();
        List<Long> okLatency = results.stream().filter(s -> s.outcome() == Outcome.SUCCESS)
                .map(Sample::latencyMs).sorted(Comparator.naturalOrder()).toList();
        System.out.printf("%-34s %-6s %7d %6d %8d %7s %7s %8d%n",
                label, coalesce ? "ON" : "OFF", success, busy, deadline,
                percentile(okLatency, 50), percentile(okLatency, 95), mirror.upstreamCalls.get());

        // Directional guard rails (not exact counts — timing varies):
        if (sameTitle && staggerMs == 0) {
            if (coalesce) {
                assertThat(success).as("coalesced same-title herd should nearly all succeed").isGreaterThanOrEqualTo(DEVICES - 5);
                assertThat(mirror.upstreamCalls.get()).as("coalesced same-title herd is one upstream resolve").isEqualTo(1);
            } else {
                assertThat(success).as("un-coalesced same-title herd is capped near the admission width").isLessThan(DEVICES / 2);
            }
        }
        if (!sameTitle) {
            // Distinct titles: coalescing cannot and must not widen the upstream ceiling.
            assertThat(mirror.upstreamCalls.get()).as("distinct-title upstream work is not reduced by coalescing")
                    .isGreaterThan(1);
        }
    }

    private Outcome oneRequest(MirrorBulkhead bulkhead, PlaybackCoalescer coalescer, MockMirror mirror,
                               boolean coalesce, String title, long budgetMs, long startNanos) {
        long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        // Controller-style local fast-path before any admission.
        String cached = mirror.cached(title);
        if (cached != null) return Outcome.SUCCESS;
        try {
            if (!coalesce) {
                // Before: every caller admits its own worker slot, exactly as the controller did pre-change.
                CompletableFuture<ResponseEntity<String>> cf =
                        bulkhead.submitFuture(() -> mirror.resolve(title, deadlineNanos));
                cf.get(budgetMs + 2_000, TimeUnit.MILLISECONDS);
                return Outcome.SUCCESS;
            }
            // After: same-key callers coalesce onto one admission (the controller's scoped path).
            String key = "sess\u0000" + title + "\u0000scope";
            DeferredResult<ResponseEntity<String>> dr = coalescer.master(key, budgetMs,
                    () -> bulkhead.submitFuture(() -> mirror.resolve(title, deadlineNanos)));
            return observe(dr, budgetMs + 2_000);
        } catch (ExecutionException ex) {
            return classify(ex.getCause());
        } catch (TimeoutException ex) {
            return Outcome.DEADLINE;
        } catch (RuntimeException ex) {
            return classify(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Outcome.OTHER;
        }
    }

    private Outcome observe(DeferredResult<ResponseEntity<String>> dr, long budgetMs) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        while (System.nanoTime() < end) {
            Object r = dr.getResult();
            if (r != null) return classify(r);
            try {
                Thread.sleep(2);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Outcome.OTHER;
            }
        }
        return Outcome.DEADLINE;
    }

    private static Outcome classify(Object r) {
        if (r instanceof ResponseEntity<?> re && re.getStatusCode().is2xxSuccessful()) return Outcome.SUCCESS;
        if (r instanceof MirrorRequestLimit.Busy) return Outcome.BUSY;
        if (r instanceof MirrorRequestLimit.DeadlineExceeded) return Outcome.DEADLINE;
        if (r instanceof Throwable t) {
            if (t instanceof MirrorRequestLimit.Busy) return Outcome.BUSY;
            if (t instanceof MirrorRequestLimit.DeadlineExceeded) return Outcome.DEADLINE;
        }
        return Outcome.OTHER;
    }

    private static String percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return "-";
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return String.valueOf(sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1))));
    }
}
