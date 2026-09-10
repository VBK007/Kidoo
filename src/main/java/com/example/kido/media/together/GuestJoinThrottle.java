package com.example.kido.media.together;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caps how fast one address may try join codes.
 *
 * <p>Asking for a guest seat is the only endpoint in this application that both takes
 * no credential and reaches the database, so it is the one place where guessing is
 * worth doing. A six-character code out of roughly 887 million is far too many to guess
 * by hand and not nearly enough to survive an unthrottled script.
 *
 * <p>Deliberately crude: an in-memory counter per address over a fixed window, lost on
 * restart. A determined attacker with many addresses is not stopped by this, and the
 * thing that actually protects a party is that a correct guess still only reaches a
 * host who has to tap accept. This closes the cheap attack, not every attack.
 */
@Component
public class GuestJoinThrottle {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * Beyond this many tracked addresses the map is cleared rather than grown.
     *
     * <p>Losing the counters is the right failure: the alternative is an unbounded map
     * fed by whoever is attacking it, which turns a rate limiter into the outage.
     */
    private static final int MAX_TRACKED = 10_000;

    private final Map<String, Attempts> byAddress = new ConcurrentHashMap<>();

    /** Attempts allowed per address per minute. Generous for a person, useless for a script. */
    private final int maxAttempts;

    public GuestJoinThrottle(
            @Value("${app.parties.guest-join-attempts-per-minute:10}") int maxAttempts) {
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
