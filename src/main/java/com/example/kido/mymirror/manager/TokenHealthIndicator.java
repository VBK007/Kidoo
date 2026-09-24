package com.example.kido.mymirror.manager;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports how many sessions hold live mirror tokens. Never DOWN: tokens exist only after a
 * user asks for something, and a mirror being away must not mark the whole server unhealthy.
 * UNKNOWN ranks below UP, so an idle mirror does not drag the overall status down either.
 */
@Component
public class TokenHealthIndicator implements HealthIndicator {
    private final TokenManager tokenManager;
    public TokenHealthIndicator(TokenManager tokenManager) { this.tokenManager = tokenManager; }

    @Override
    public Health health() {
        int live = tokenManager.liveSessions();
        return (live > 0 ? Health.up() : Health.unknown()).withDetail("liveSessions", live).build();
    }
}
