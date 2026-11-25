package com.example.kido.mymirror;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
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
        return submit(action, timeout, null, workers, "foreground");
    }

    public <T> DeferredResult<T> submitOptional(Callable<T> action, Duration budget, T fallback) {
        return submit(action, budget, fallback, optional, "home");
    }

    private <T> DeferredResult<T> submit(Callable<T> action, Duration budget, T fallback,
                                         ThreadPoolExecutor executor, String lane) {
        long deadline = MirrorRequestContext.deadlineWithin(budget.compareTo(timeout) < 0 ? budget : timeout);
        var headers = ClientHeaderSupport.current();
        String attempt = MDC.get(MirrorRequestContext.ATTEMPT);
        long started = System.nanoTime();
        var result = new DeferredResult<T>(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - started)));
        Runnable expired = () -> fail(result, fallback, new MirrorRequestLimit.DeadlineExceeded());
        long queueDeadline = Math.min(deadline, started + queueTimeout.toNanos());
        Runnable queueExpired = () -> {
            metrics.counter("mirror.executor.queue.expired", "lane", lane).increment();
            if (queueDeadline == deadline) expired.run();
            else fail(result, fallback, new MirrorRequestLimit.Busy());
        };
        var task = new AdmissionTask(() -> {
            if (result.isSetOrExpired()) return null;
            if (System.nanoTime() >= queueDeadline && queueDeadline < deadline) {
                metrics.counter("mirror.executor.queue.expired", "lane", lane).increment();
                fail(result, fallback, new MirrorRequestLimit.Busy());
                return null;
            }
            if (System.nanoTime() >= deadline) {
                expired.run();
                return null;
            }
            try (var ignored = MDC.putCloseable(MirrorRequestContext.ATTEMPT, attempt)) {
                var value = MirrorRequestContext.withinDeadline(deadline, () -> ClientHeaderSupport.callAs(headers,
                        () -> executor == optional ? MirrorRequestContext.optional(action) : action.call()));
                result.setResult(value);
            } catch (Exception ex) {
                fail(result, fallback, ex);
            }
            return null;
        }, queueDeadline, queueExpired);
        Runnable cancel = () -> {
            task.cancel(true);
            executor.remove(task);
        };
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ex) {
            metrics.counter("mirror.executor.rejected", "lane", lane).increment();
            fail(result, fallback, new MirrorRequestLimit.Busy());
            return result;
        }
        result.onTimeout(() -> {
            expired.run();
            cancel.run();
        });
        result.onError(ex -> cancel.run());
        result.onCompletion(cancel);
        return result;
    }

    private static <T> void fail(DeferredResult<T> result, T fallback, Exception ex) {
        if (fallback == null) result.setErrorResult(ex);
        else result.setResult(fallback);
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
        optional.shutdownNow();
        queueExpiry.shutdownNow();
    }
}
