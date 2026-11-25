package com.example.kido.mymirror;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;

@Component
public class MirrorBulkhead {
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor optional;
    private final Duration timeout;
    private final Duration queueTimeout;
    private final ScheduledThreadPoolExecutor queueExpiry = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("mirror-queue-expiry").factory());
    private final MeterRegistry metrics;
    private final MirrorRequestLimit upstream;

    public MirrorBulkhead(@Value("${app.mymirror.bulkhead.workers:8}") int concurrency,
                          @Value("${app.mymirror.bulkhead.queue-capacity:16}") int queue,
                          @Value("${app.mymirror.bulkhead.timeout:30s}") Duration timeout,
                          @Value("${app.mymirror.bulkhead.queue-timeout:2s}") Duration queueTimeout,
                          MeterRegistry metrics, MirrorRequestLimit upstream) {
        if (timeout.toMillis() <= 0 || queueTimeout.toMillis() <= 0) throw new IllegalArgumentException("Mirror timeout must be positive");
        this.timeout = timeout;
        this.queueTimeout = queueTimeout;
        this.metrics = metrics;
        this.upstream = upstream;
        workers = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), Thread.ofPlatform().daemon().name("mirror-request-", 0).factory());
        optional = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4),
                Thread.ofPlatform().daemon().name("mirror-home-request-", 0).factory());
        monitor(workers, "foreground");
        monitor(optional, "home");
        queueExpiry.scheduleWithFixedDelay(this::sweepQueues, 25, 25, TimeUnit.MILLISECONDS);
    }

    private void sweepQueues() {
        expireQueued(workers);
        expireQueued(optional);
    }

    private void expireQueued(ThreadPoolExecutor executor) {
        for (Runnable item : executor.getQueue()) {
            if (item instanceof AdmissionTask task && System.nanoTime() >= task.deadline
                    && executor.remove(task)) {
                task.cancel(false);
                task.expired.run();
            }
        }
    }

    public void maintenance(Runnable work) {
        try {
            optional.execute(new AdmissionTask(() -> {
                MirrorRequestContext.within(Duration.ofSeconds(8), () -> {
                    try {
                        return MirrorRequestContext.optional(() -> { work.run(); return null; });
                    } catch (Exception ex) {
                        return null;
                    }
                });
                return null;
            }, System.nanoTime() + queueTimeout.toNanos(), () -> {}));
        } catch (RejectedExecutionException ex) {
            metrics.counter("mirror.executor.rejected", "lane", "home").increment();
        }
    }

    private static final class AdmissionTask extends FutureTask<Void> {
        private final long deadline;
        private final Runnable expired;

        private AdmissionTask(Callable<Void> work, long deadline, Runnable expired) {
            super(work);
            this.deadline = deadline;
            this.expired = expired;
        }
    }

    private void monitor(ThreadPoolExecutor executor, String lane) {
        var tags = io.micrometer.core.instrument.Tags.of("lane", lane);
        metrics.gauge("mirror.executor.active", tags, executor, ThreadPoolExecutor::getActiveCount);
        metrics.gauge("mirror.executor.queue", tags, executor, value -> value.getQueue().size());
    }

    public <T> DeferredResult<T> submit(Callable<T> action) {
        upstream.checkCooldown();
        return deferred(action, timeout, null, workers, "foreground");
    }

    public <T> DeferredResult<T> submitOptional(Callable<T> action, Duration budget, T fallback) {
        return deferred(action, budget, fallback, optional, "home");
    }

    /** The foreground admission timeout, for callers that await {@link #submitFuture} directly. */
    public long timeoutMillis() {
        return timeout.toMillis();
    }

    /**
     * Admit {@code action} to the foreground pool and return a {@link CompletableFuture} for its result —
     * the same admission {@link #submit(Callable)} performs (cooldown check, one worker slot, queue-timeout,
     * deadline), but exposed as a future many callers can await instead of a single HTTP {@link DeferredResult}.
     * It completes with {@link MirrorRequestLimit.Busy} when the pool is saturated and
     * {@link MirrorRequestLimit.DeadlineExceeded} on timeout, exactly as {@link #submit} does.
     *
     * <p>This lets the HLS master coalesce a cold-start burst onto a single admission: the first caller for a
     * title admits here and the rest await this one future, so a 200-device burst on one title spends one
     * worker slot, not two hundred. It does not widen upstream concurrency — the admitted work still passes
     * through the same single-slot, paced upstream limiter.
     */
    public <T> CompletableFuture<T> submitFuture(Callable<T> action) {
        upstream.checkCooldown();
        return admit(action, MirrorRequestContext.deadlineWithin(timeout), null, workers, "foreground");
    }

    private <T> DeferredResult<T> deferred(Callable<T> action, Duration budget, T fallback,
                                           ThreadPoolExecutor executor, String lane) {
        long deadline = MirrorRequestContext.deadlineWithin(budget.compareTo(timeout) < 0 ? budget : timeout);
        long started = System.nanoTime();
        var result = new DeferredResult<T>(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - started)));
        CompletableFuture<T> admitted = admit(action, deadline, fallback, executor, lane);
        admitted.whenComplete((value, ex) -> {
            if (result.isSetOrExpired()) return;
            if (ex == null) result.setResult(value);
            else result.setErrorResult(unwrap(ex));
        });
        // Servlet-level backstop: if the async request times out before the admitted future lands (it
        // should not — the future completes at the same deadline), answer with the same DeadlineExceeded.
        result.onTimeout(() -> result.setErrorResult(new MirrorRequestLimit.DeadlineExceeded()));
        return result;
    }

    /**
     * The shared admission core. Completes the returned future on every terminal path — success, action
     * failure, synchronous rejection at capacity ({@link MirrorRequestLimit.Busy}), queue expiry, and
     * deadline ({@link MirrorRequestLimit.DeadlineExceeded}) — so a {@link DeferredResult} or a direct future
     * caller sees identical outcomes.
     */
    private <T> CompletableFuture<T> admit(Callable<T> action, long deadline, T fallback,
                                           ThreadPoolExecutor executor, String lane) {
        var headers = ClientHeaderSupport.current();
        String attempt = MDC.get(MirrorRequestContext.ATTEMPT);
        long started = System.nanoTime();
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable expired = () -> settle(result, fallback, new MirrorRequestLimit.DeadlineExceeded());
        long queueDeadline = Math.min(deadline, started + queueTimeout.toNanos());
        Runnable queueExpired = () -> {
            metrics.counter("mirror.executor.queue.expired", "lane", lane).increment();
            if (queueDeadline == deadline) expired.run();
            else settle(result, fallback, new MirrorRequestLimit.Busy());
        };
        var task = new AdmissionTask(() -> {
            if (result.isDone()) return null;
            if (System.nanoTime() >= queueDeadline && queueDeadline < deadline) {
                metrics.counter("mirror.executor.queue.expired", "lane", lane).increment();
                settle(result, fallback, new MirrorRequestLimit.Busy());
                return null;
            }
            if (System.nanoTime() >= deadline) {
                expired.run();
                return null;
            }
            try (var ignored = MDC.putCloseable(MirrorRequestContext.ATTEMPT, attempt)) {
                var value = MirrorRequestContext.withinDeadline(deadline, () -> ClientHeaderSupport.callAs(headers,
                        () -> executor == optional ? MirrorRequestContext.optional(action) : action.call()));
                result.complete(value);
            } catch (Exception ex) {
                settle(result, fallback, ex);
            }
            return null;
        }, queueDeadline, queueExpired);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ex) {
            metrics.counter("mirror.executor.rejected", "lane", lane).increment();
            settle(result, fallback, new MirrorRequestLimit.Busy());
            return result;
        }
        result.whenComplete((value, ex) -> {
            task.cancel(false);
            executor.remove(task);
        });
        return result;
    }

    private static <T> void settle(CompletableFuture<T> result, T fallback, Throwable ex) {
        if (fallback == null) result.completeExceptionally(ex);
        else result.complete(fallback);
    }

    private static Throwable unwrap(Throwable ex) {
        return ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
        optional.shutdownNow();
        queueExpiry.shutdownNow();
    }
}
