package com.example.kido.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.kido.mymirror.SessionRecoveryCoordinator;
import com.example.kido.mymirror.SessionRecoveryCoordinator.Status;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** Optional, best-effort alert delivery. The recovery journal remains the source of truth. */
@Component
public class MirrorIncidentNotifier {
    private final SessionRecoveryCoordinator recovery;
    private final MeterRegistry meters;
    private final URI destination;
    private final HttpClient client;
    private final AtomicBoolean sending = new AtomicBoolean();
    private String lastNotification;

    public MirrorIncidentNotifier(SessionRecoveryCoordinator recovery, MeterRegistry meters,
            @Value("${app.mymirror.recovery.notification-webhook:}") String webhook) {
        this.recovery = recovery;
        this.meters = meters;
        if (webhook.isBlank()) {
            destination = null;
            client = null;
        } else {
            try {
                destination = new URI(webhook);
            } catch (java.net.URISyntaxException ex) {
                throw new IllegalArgumentException("Mirror notification webhook must be an HTTPS endpoint");
            }
            if (!"https".equalsIgnoreCase(destination.getScheme()) || destination.getHost() == null
                    || destination.getUserInfo() != null || destination.getFragment() != null) {
                throw new IllegalArgumentException("Mirror notification webhook must be an HTTPS endpoint");
            }
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }
    }

    @Scheduled(fixedDelayString = "${app.mymirror.recovery.notification-poll:5s}")
    void scan() {
        if (destination == null) return;
        Status status = recovery.status();
        String event = event(status);
        if (event == null) return;
        String key = status.attemptId() + ":" + event;
        if (key.equals(lastNotification) || !sending.compareAndSet(false, true)) return;
        lastNotification = key;
        try {
            var request = HttpRequest.newBuilder(destination).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload(status, event))).build();
            var responseFuture = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            java.util.concurrent.CompletableFuture.delayedExecutor(6, java.util.concurrent.TimeUnit.SECONDS)
                    .execute(() -> responseFuture.cancel(true));
            responseFuture.whenComplete((response, error) -> {
                        try {
                            record(error == null && response.statusCode() / 100 == 2);
                        } finally {
                            sending.set(false);
                        }
                    });
        } catch (RuntimeException ex) {
            sending.set(false);
            record(false);
        }
    }

    static String event(Status status) {
        if (status.incidentId() == null || status.attemptId() == null) return null;
        return switch (status.state()) {
            case AWAITING_OPERATOR, ACQUIRING -> "OPERATOR_REQUIRED";
            case FAILED -> "RECOVERY_FAILED";
            case TIMED_OUT -> "RECOVERY_TIMED_OUT";
            default -> null;
        };
    }

    static String payload(Status status, String event) {
        return JsonMapper.builder().build().writeValueAsString(Map.of("event", event,
                "incidentId", status.incidentId(), "attemptId", status.attemptId(),
                "state", status.state(), "updatedAt", status.updatedAt()));
    }

    private void record(boolean delivered) {
        meters.counter("mirror.recovery.notifications", "outcome", delivered ? "sent" : "failed").increment();
        if (!delivered) LoggerFactory.getLogger(getClass()).warn("Mirror recovery notification delivery failed");
    }
}
