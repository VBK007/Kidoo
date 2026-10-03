package com.example.kido.site;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caps how fast one address may record page views.
 *
 * <p>Same shape as {@code GuestJoinThrottle}: this is the only endpoint in the
 * application that takes no credential at all and writes to the database on every
 * call, so it is the one place a flood script gets something for free that a real
 * visitor never would — nobody's browser loads the landing page 30 times a minute.
 *
 * <p>Deliberately crude: an in-memory counter per address over a fixed window, lost on
 * restart. This closes the cheap, unauthenticated flood, not every attack — a
 * determined attacker with many addresses can still pad the count, which is why the
 * admin traffic view is presented as an approximate, self-reported figure rather than
 * a number anything else depends on.
 */
@Component
public class SiteVisitThrottle {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Beyond this many tracked addresses the map is cleared rather than grown. */
    private static final int MAX_TRACKED = 10_000;

    private final Map<String, Attempts> byAddress = new ConcurrentHashMap<>();

    /** Visits allowed per address per minute. Generous for a person loading a page. */
    private final int maxAttempts;

    public SiteVisitThrottle(@Value("${app.site.visit-attempts-per-minute:30}") int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /** @return false when this address has had its allowance for the current window */
    public boolean allow(String address) {
        if (address == null || address.isBlank()) {
            return true;
        }
        if (byAddress.size() > MAX_TRACKED) {
            byAddress.clear();
        }

        long now = System.currentTimeMillis();
        Attempts attempts = byAddress.compute(address, (key, existing) ->
                existing == null || now - existing.startedAt > WINDOW.toMillis()
                        ? new Attempts(now)
                        : existing);

        return attempts.count.incrementAndGet() <= maxAttempts;
    }

    private static final class Attempts {

        private final long startedAt;
        private final AtomicInteger count = new AtomicInteger();

        private Attempts(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
