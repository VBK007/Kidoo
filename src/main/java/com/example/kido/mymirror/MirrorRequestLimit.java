package com.example.kido.mymirror;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import com.example.kido.common.ApiException;

@Component
public class MirrorRequestLimit implements ClientHttpRequestInterceptor {
    private static final Logger log = LoggerFactory.getLogger(MirrorRequestLimit.class);
    private static final long DEFAULT_COOLDOWN_SECONDS = 60;
    private final AtomicLong resumeAt = new AtomicLong();
    private final Semaphore requestSlot;
    private final long intervalNanos;
    private final AtomicInteger inFlight = new AtomicInteger();
    private long nextStart;

    public MirrorRequestLimit() {
        this(1, 0);
    }

    @Autowired
    public MirrorRequestLimit(@Value("${app.mymirror.upstream.max-concurrent:1}") int maxConcurrent,
                              @Value("${app.mymirror.upstream.min-interval-ms:100}") long minIntervalMs) {
        if (maxConcurrent < 1 || minIntervalMs < 0) {
            throw new IllegalArgumentException("Invalid mirror upstream request limits");
        }
        requestSlot = new Semaphore(maxConcurrent, true);
        intervalNanos = TimeUnit.MILLISECONDS.toNanos(minIntervalMs);
        nextStart = System.nanoTime();
    }

    @Autowired
    void registerMetrics(MeterRegistry registry) {
        registry.gauge("mirror.upstream.inflight", inFlight);
        registry.gauge("mirror.upstream.cooldown.seconds", resumeAt,
                value -> Math.max(0, value.get() - Instant.now().toEpochMilli()) / 1_000d);
    }

    private synchronized long reserveStart(long deadline) {
        long now = System.nanoTime();
        long start = Math.max(now, nextStart);
        if (start > deadline) throw new Busy();
        nextStart = start + intervalNanos;
        return start - now;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        long started = System.nanoTime();
        long deadline = MirrorRequestContext.deadlineWithin(MirrorRequestContext.isOptional() ? Duration.ofMillis(250) : Duration.ofSeconds(2));
        try {
            checkCooldown();
        } catch (Throttled ex) {
            logRequest(request, started, 429, inFlight.get(), false, 0, "cooldown");
            throw ex;
        }
        try {
            if ((MirrorRequestContext.isOptional() && requestSlot.hasQueuedThreads()) || !requestSlot.tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                logRequest(request, started, 503, inFlight.get(), false, System.nanoTime() - started, "admission_busy");
                throw new Busy();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the streaming source", ex);
        }

        ClientHttpResponse response = null;
        int status = 0;
        boolean dispatched = false;
        long queued = 0;
        try {
            checkCooldown();
            TimeUnit.NANOSECONDS.sleep(reserveStart(deadline));
            checkCooldown();
            queued = System.nanoTime() - started;
            inFlight.incrementAndGet();
            dispatched = true;
            response = execution.execute(request, body);
            status = response.getStatusCode().value();
            if (status == 429) {
                throw throttled(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            }
            if ((status == 403 || status == 503) && "challenge".equalsIgnoreCase(
                    response.getHeaders().getFirst("cf-mitigated"))) {
                throw new AccessDenied();
            }
            if (status >= 300 && status < 400 && request.getMethod() != HttpMethod.HEAD) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Unexpected redirect from the streaming source");
            }
            return new LimitedResponse(response, request, started, status, queued);
        } catch (IOException | RuntimeException | InterruptedException ex) {
            try {
                if (response != null) response.close();
            } finally {
                int active = dispatched ? inFlight.getAndDecrement() : inFlight.get();
                requestSlot.release();
                int outcomeStatus = ex instanceof ApiException api ? api.getStatus().value() : status;
                String outcome = dispatched ? (status == 0 ? "transport_error" : "upstream_response")
                        : ex instanceof Throttled ? "cooldown" : "admission_failed";
                logRequest(request, started, outcomeStatus, active, dispatched,
                        dispatched ? queued : System.nanoTime() - started, outcome, status);
            }
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the streaming source", ex);
            }
            if (ex instanceof IOException io) throw io;
            throw (RuntimeException) ex;
        }
    }

    private void logRequest(HttpRequest request, long started, int status, int active,
                            boolean dispatched, long queued, String outcome) {
        logRequest(request, started, status, active, dispatched, queued, outcome, dispatched ? status : 0);
    }

    private void logRequest(HttpRequest request, long started, int status, int active,
                            boolean dispatched, long queued, String outcome, int upstreamStatus) {
        String path = java.util.Objects.requireNonNullElse(request.getURI().getPath(), "");
        String type = switch (path.substring(path.lastIndexOf('/') + 1)) {
            case "p.php" -> "heartbeat";
            case "post.php" -> "title_detail";
            case "playlist.php" -> "playlist";
            case "verify2.php" -> "session_verify";
            case "check.php" -> "discovery";
            case "search.php", "episodes.php", "homepage.php" -> "browse";
            default -> path.endsWith(".m3u8") ? "manifest" : "other";
        };
        Metrics.timer("mirror.upstream.latency", "type", type, "status", Integer.toString(status))
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        log.atInfo().addKeyValue("playbackAttemptId", MDC.get(MirrorRequestContext.ATTEMPT))
                .addKeyValue("requestType", type).addKeyValue("status", status)
                .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .addKeyValue("inFlight", active).addKeyValue("dispatched", dispatched)
                .addKeyValue("upstreamStatus", upstreamStatus == 0 ? null : upstreamStatus)
                .addKeyValue("admissionOutcome", outcome)
                .addKeyValue("outboundWaitMs", TimeUnit.NANOSECONDS.toMillis(queued))
                .addKeyValue("cooldownRemainingMs", Math.max(0, resumeAt.get() - Instant.now().toEpochMilli()))
                .log("Mirror upstream request");
    }

    public void checkCooldown() {
        long remaining = resumeAt.get() - Instant.now().toEpochMilli();
        if (remaining > 0) throw new Throttled(remaining);
    }

    public Throttled throttled(String retryAfter) {
        long now = Instant.now().toEpochMilli();
        long until = now + DEFAULT_COOLDOWN_SECONDS * 1_000;
        String original = null;
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds < 0) throw new NumberFormatException();
                until = Math.addExact(now, Math.multiplyExact(1_000L, seconds));
                original = retryAfter.trim();
            } catch (NumberFormatException | ArithmeticException ex) {
                try {
                    until = Math.max(now, ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli());
                    original = retryAfter;
                } catch (DateTimeParseException ignored) {
                    // Use the default cooldown when the upstream header is invalid.
                }
            }
        }
        long resume = resumeAt.accumulateAndGet(until, Math::max);
        return new Throttled(resume - now, resume == until ? original : null);
    }

    public static final class DeadlineExceeded extends ApiException {
        public DeadlineExceeded() {
            super(HttpStatus.GATEWAY_TIMEOUT, "Upstream request deadline exceeded");
        }
    }

    public static final class AccessDenied extends ApiException {
        public AccessDenied() {
            super(HttpStatus.FORBIDDEN, "The streaming source denied access");
        }
    }

    public static final class Busy extends ApiException {
        public Busy() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "The streaming source is busy");
        }
    }

    public static final class Throttled extends ApiException {
        private final long retryAfterSeconds;

        private final String retryAfterHeader;

        private Throttled(long remainingMillis) {
            this(remainingMillis, null);
        }

        private Throttled(long remainingMillis, String originalHeader) {
            super(HttpStatus.TOO_MANY_REQUESTS, "The streaming source has temporarily limited requests");
            this.retryAfterSeconds = Math.ceilDiv(remainingMillis, 1_000);
            this.retryAfterHeader = originalHeader == null ? Long.toString(retryAfterSeconds) : originalHeader;
        }

        public String retryAfterHeader() {
            return retryAfterHeader;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final class LimitedResponse implements ClientHttpResponse {
        private final ClientHttpResponse response;
        private final HttpRequest request;
        private final long started;
        private final int status;
        private final long queued;
        private boolean closed;

        private LimitedResponse(ClientHttpResponse response, HttpRequest request, long started, int status, long queued) {
            this.response = response;
            this.request = request;
            this.started = started;
            this.status = status;
            this.queued = queued;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return response.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return response.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return response.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            return response.getBody();
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                response.close();
            } finally {
                int active = inFlight.getAndDecrement();
                requestSlot.release();
                logRequest(request, started, status, active, true, queued, "upstream_response");
            }
        }
    }
}
