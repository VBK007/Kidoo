package com.example.kido.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The real client address behind a reverse proxy.
 *
 * <p>{@code request.getRemoteAddr()} only ever returns the immediate TCP peer — here
 * that is always the {@code caddy} or {@code cloudflared} container, not the phone. Both
 * ingress paths sit directly in front of this app with no other hop, so the first value
 * in these headers is trustworthy without needing to walk a longer chain.
 */
public final class ClientAddress {

    private ClientAddress() {}

    /**
     * Cloudflare sets CF-Connecting-IP to the real client IP; X-Forwarded-For
     * is a fallback for other proxies. Both are attacker-controllable if the
     * request reaches us directly, but here Cloudflare's tunnel and Caddy are
     * the only paths in, so CF-Connecting-IP can be trusted.
     */
    public static String resolve(HttpServletRequest request) {
        String cf = request.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return cf;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
