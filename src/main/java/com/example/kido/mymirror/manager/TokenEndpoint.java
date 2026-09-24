package com.example.kido.mymirror.manager;

import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/** Counts live token sessions. The tokens themselves are per-user credentials and stay private. */
@Component
@Endpoint(id = "tokens")
public class TokenEndpoint {
    private final TokenManager tokenManager;
    public TokenEndpoint(TokenManager tokenManager) { this.tokenManager = tokenManager; }

    @ReadOperation
    public Map<String, Object> tokens() {
        return Map.of("liveSessions", tokenManager.liveSessions());
    }
}
