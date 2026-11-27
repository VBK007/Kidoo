package com.example.kido.mymirror;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class MirrorRequestContext extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(MirrorRequestContext.class);
    public static final String ATTEMPT = "playbackAttemptId";
    private static final ThreadLocal<Boolean> OPTIONAL = new ThreadLocal<>();
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private static final ThreadLocal<AtomicInteger> BACKGROUND_BUDGET = new ThreadLocal<>();
    private final MeterRegistry metrics;
    private final AtomicInteger active = new AtomicInteger();

    public MirrorRequestContext(MeterRegistry metrics) {
        this.metrics = metrics;
        metrics.gauge("mirror.playback.http.active", active);
    }

    public static long deadlineWithin(Duration duration) {
        Long deadline = DEADLINE.get();
        long proposed = System.nanoTime() + duration.toNanos();
        return deadline == null ? proposed : Math.min(deadline, proposed);
    }

    public static <T> T within(Duration duration, Supplier<T> action) {
        Long previous = DEADLINE.get();
        DEADLINE.set(deadlineWithin(duration));
        try {
            return action.get();
        } finally {
            if (previous == null) DEADLINE.remove();
            else DEADLINE.set(previous);
        }
    }

    public static <T> T withinDeadline(long deadline, Callable<T> action) throws Exception {
        Long previous = DEADLINE.get();
        DEADLINE.set(previous == null ? deadline : Math.min(previous, deadline));
        try {
            return action.call();
        } finally {
            if (previous == null) DEADLINE.remove();
            else DEADLINE.set(previous);
        }
    }

    public static AtomicInteger backgroundBudget() {
        return BACKGROUND_BUDGET.get();
    }

    public static <T> T background(AtomicInteger budget, Callable<T> action) throws Exception {
        if (budget == null) return action.call();
        AtomicInteger previous = BACKGROUND_BUDGET.get();
        BACKGROUND_BUDGET.set(budget);
        try {
            return action.call();
        } finally {
            if (previous == null) BACKGROUND_BUDGET.remove();
            else BACKGROUND_BUDGET.set(previous);
        }
    }

    public static boolean isOptional() {
        return Boolean.TRUE.equals(OPTIONAL.get());
    }

    public static <T> T optional(Callable<T> action) throws Exception {
        OPTIONAL.set(true);
        try {
            return action.call();
        } finally {
            OPTIONAL.remove();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/playback/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String id = UUID.randomUUID().toString();
        String correlation = request.getHeader("X-Playback-Attempt-Id");
        boolean clientId = correlation != null && correlation.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}");
        if (clientId) {
            id = correlation.toLowerCase(java.util.Locale.ROOT);
        }
        response.setHeader("X-Playback-Attempt-Id", id);
        response.setHeader("Cache-Control", "private, no-store");
        long started = System.nanoTime();
        String route = routeKind(request.getServletPath());
        var completed = new AtomicBoolean();
        String attempt = id;
        active.incrementAndGet();
        Runnable finish = () -> {
            if (completed.compareAndSet(false, true)) {
                active.decrementAndGet();
                logCompletion(attempt, route, clientId, response.getStatus(), started);
            }
        };
        DEADLINE.set(deadlineWithin(Duration.ofSeconds(30)));
        try (var ignored = MDC.putCloseable(ATTEMPT, id)) {
            chain.doFilter(request, response);
        } finally {
            DEADLINE.remove();
            if (request.isAsyncStarted()) {
                try {
                    request.getAsyncContext().addListener(new jakarta.servlet.AsyncListener() {
                        public void onComplete(jakarta.servlet.AsyncEvent event) {
                            finish.run();
                        }
                        public void onTimeout(jakarta.servlet.AsyncEvent event) {}
                        public void onError(jakarta.servlet.AsyncEvent event) {}
                        public void onStartAsync(jakarta.servlet.AsyncEvent event) {
                            event.getAsyncContext().addListener(this);
                        }
                    });
                } catch (IllegalStateException ex) {
                    finish.run();
                }
            } else {
                finish.run();
            }
        }
    }
    private static String routeKind(String servletPath) {
        return switch (servletPath) {
            case "/playback/hls" -> "master";
            case "/playback/playlist" -> "playlist";
            case "/playback/heartbeat" -> "heartbeat";
            case "/playback/variant" -> "variant";
            case "/playback/subtitle" -> "subtitle";
            case "/playback/alt", "/playback/streamtape" -> "alternate";
            default -> "other";
        };
    }

    private void logCompletion(String id, String route, boolean clientId, int status, long started) {
        long elapsed = System.nanoTime() - started;
        metrics.timer("mirror.playback.http.duration", "operation", route, "status", Integer.toString(status))
                .record(elapsed, TimeUnit.NANOSECONDS);
        log.atInfo().addKeyValue("playbackAttemptId", id)
                .addKeyValue("operation", route)
                .addKeyValue("attemptSource", clientId ? "client" : "server")
                .addKeyValue("status", status)
                .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(elapsed))
                .log("Mirror playback request");
    }
}
