package com.example.kido.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.kido.mymirror.MirrorSessionProvider;
import com.example.kido.mymirror.SessionRecoveryCoordinator;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class MirrorOperations {
    public record Lane(double active, double queued, double rejected, double expired) {}
    public record Snapshot(String sessionState, String observedAvailability, SessionRecoveryCoordinator.Status recovery,
                           double activePlaybackRequests, double upstreamInFlight, double cooldownSeconds,
                           Map<String, Lane> executors, Map<String, Double> masterCache, Map<String, Double> homeCache,
                           Map<String, Double> tokenRefresh, long playbackRequests, long playbackErrors,
                           double playbackErrorPercent, Map<String, Long> playbackErrorsByStatus,
                           Map<String, Double> recoveryOutcomes) {}

    private final MeterRegistry meters;
    private final MirrorSessionProvider sessions;
    private final SessionRecoveryCoordinator recovery;

    public MirrorOperations(MeterRegistry meters, MirrorSessionProvider sessions, SessionRecoveryCoordinator recovery) {
        this.meters = meters;
        this.sessions = sessions;
        this.recovery = recovery;
    }

    public Snapshot snapshot() {
        var state = sessions.state();
        String availability = switch (state) {
            case VALID -> "AVAILABLE";
            case UNKNOWN -> "UNKNOWN";
            case THROTTLED -> "THROTTLED";
            case TEMPORARILY_UNAVAILABLE -> "UNAVAILABLE";
            case MISSING, REVOKED, ACCESS_DENIED -> "CREDENTIAL_UNAVAILABLE";
        };
        Map<String, Lane> lanes = new LinkedHashMap<>();
        for (String lane : List.of("foreground", "home")) {
            lanes.put(lane, new Lane(gauge("mirror.executor.active", lane), gauge("mirror.executor.queue", lane),
                    counter("mirror.executor.rejected", "lane", lane), counter("mirror.executor.queue.expired", "lane", lane)));
        }
        Map<String, Long> statuses = new java.util.TreeMap<>();
        long calls = 0;
        long errors = 0;
        for (Timer timer : meters.find("mirror.playback.http.duration").timers()) {
            long count = timer.count();
            calls += count;
            String status = timer.getId().getTag("status");
            if (status != null && (status.startsWith("4") || status.startsWith("5"))) {
                errors += count;
                statuses.merge(status, count, Long::sum);
            }
        }
        return new Snapshot(state.name(), availability, recovery.status(), gauge("mirror.playback.http.active", null),
                gauge("mirror.upstream.inflight", null), gauge("mirror.upstream.cooldown.seconds", null), lanes,
                breakdown("mirror.master.cache", "outcome", "hit", "miss", "joined", "bypass"),
                breakdown("mirror.home.cache", "outcome", "hit", "miss"),
                breakdown("mirror.token.refresh", "outcome", "attempt", "success", "failure"), calls, errors,
                calls == 0 ? 0 : 100d * errors / calls, statuses,
                breakdown("mirror.recovery.transitions", "state", "SUCCEEDED", "FAILED", "TIMED_OUT"));
    }

    private Map<String, Double> breakdown(String name, String tag, String... values) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String value : values) result.put(value, counter(name, tag, value));
        return result;
    }

    private double gauge(String name, String lane) {
        var search = meters.find(name);
        if (lane != null) search.tag("lane", lane);
        var gauge = search.gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private double counter(String name, String tag, String value) {
        var counter = meters.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }
}
