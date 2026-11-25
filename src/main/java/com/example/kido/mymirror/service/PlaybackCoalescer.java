package com.example.kido.mymirror.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Pre-admission coalescing for the HLS master, so a cold-start burst on one title spends a single
 * worker slot instead of one per device.
 *
 * <p>The master already single-flights <em>inside</em> {@link PlaybackService#hlsMaster} — the first
 * caller resolves upstream and later callers share its {@link java.util.concurrent.CompletableFuture}.
 * But that join happens only after a request has been admitted through the foreground bulkhead. In a
 * cold start the title is not yet cached, so N simultaneous requests for it all see no cache and all
 * try to admit; the bulkhead's small worker pool rejects most of them ({@code Busy}/503) before any of
 * them reaches the in-worker join. The window is the ~1–2s it takes the first resolve to populate the
 * master cache, after which every later request takes the local cache fast-path and never touches a
 * worker at all.
 *
 * <p>This closes that window at the door: the first caller for a key admits through the bulkhead (via
 * {@code owner.get()}, which is {@link com.example.kido.mymirror.MirrorBulkhead#submitFuture}); every
 * other caller with the same key — same session, same title, same scope — awaits that one admission
 * instead of trying its own. So one hot title in a 200-device burst consumes one worker slot and one
 * upstream resolution, not two hundred. It does not widen upstream concurrency or skip any
 * provider-required call: the owner still passes through the unchanged single-slot, paced upstream
 * limiter. It only stops identical requests from stampeding the admission gate.
 *
 * <p>The key (see {@link PlaybackService#masterCoalesceKey}) is {@code sign(cookie) + id + scope}. It
 * deliberately omits the origin (stable within the 5-minute dispatcher cache; a mid-flight host flip is
 * the failover path's job) and the session generation (a function of cookie state, already discriminated
 * by {@code sign(cookie)}). Grouping is conservative: distinct sessions never share, because their cookie
 * signatures differ; the worst case is under-coalescing, which is merely one extra admission, never a
 * cross-session leak. The entry lives only for the resolve; it is removed the instant the shared future
 * completes, after which the master cache serves everyone.
 *
 * <p>Enabled by {@code app.mymirror.coalesce.enabled} (default true). Disabled, every caller admits
 * independently, exactly as before this component existed.
 */
@Component
public class PlaybackCoalescer {
    private final boolean enabled;
    private final MeterRegistry metrics;
    private final ConcurrentHashMap<String, CompletableFuture<ResponseEntity<String>>> inFlight = new ConcurrentHashMap<>();

    public PlaybackCoalescer(@Value("${app.mymirror.coalesce.enabled:true}") boolean enabled, MeterRegistry metrics) {
        this.enabled = enabled;
        this.metrics = metrics;
        metrics.gauge("mirror.coalesce.inflight", this, c -> c.inFlight.size());
    }

    /**
     * Admit {@code owner} once per {@code key} and hand every concurrent caller of the same key the same
     * result. The owner (the first caller to install the key) runs {@code owner.get()} — a single bulkhead
     * admission — and the rest join its future. The returned {@link DeferredResult} carries the shared
     * outcome, success or failure, with the deadline the admission itself enforces.
     *
     * <p>If {@code owner.get()} throws synchronously (e.g. an active upstream cooldown surfaced by
     * {@code checkCooldown()}), no entry is installed and the throw propagates to this caller unchanged —
     * a throttle must not be coalesced into a shared success, and the next caller re-evaluates it fresh.
     */
    public DeferredResult<ResponseEntity<String>> master(String key, long timeoutMillis,
                                                         Supplier<CompletableFuture<ResponseEntity<String>>> owner) {
        if (!enabled) {
            return deliver(owner.get(), timeoutMillis);
        }
        AtomicBoolean mine = new AtomicBoolean(false);
        CompletableFuture<ResponseEntity<String>> shared = inFlight.computeIfAbsent(key, ignored -> {
            mine.set(true);
            return owner.get();
        });
        if (mine.get()) {
            metrics.counter("mirror.coalesce", "role", "owner").increment();
            // Free the slot the moment the resolve lands; after this the master cache serves callers.
            shared.whenComplete((value, ex) -> inFlight.remove(key, shared));
        } else {
            metrics.counter("mirror.coalesce", "role", "joiner").increment();
        }
        return deliver(shared, timeoutMillis);
    }

    private DeferredResult<ResponseEntity<String>> deliver(CompletableFuture<ResponseEntity<String>> future,
                                                           long timeoutMillis) {
        DeferredResult<ResponseEntity<String>> result = new DeferredResult<>(Math.max(1, timeoutMillis));
        future.whenComplete((value, ex) -> {
            if (result.isSetOrExpired()) return;
            if (ex == null) result.setResult(value);
            else result.setErrorResult(unwrap(ex));
        });
        return result;
    }

    private static Throwable unwrap(Throwable ex) {
        return ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
    }
}
