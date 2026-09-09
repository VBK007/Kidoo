package com.example.kido.common;

import java.io.IOException;

import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/** Logs one line per HTTP request with method, path, status and duration. */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long took = System.currentTimeMillis() - start;
            String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
            log.info("{} {} {}{} -> {} ({} ms)",
                    clientIp(request), request.getMethod(), request.getRequestURI(), query, response.getStatus(), took);
        }
    }

    /**
     * Cloudflare sets CF-Connecting-IP to the real client IP; X-Forwarded-For
     * is a fallback for other proxies. Both are attacker-controllable if the
     * request reaches us directly, but here Cloudflare's tunnel is the only
     * path in, so CF-Connecting-IP can be trusted.
     */
    private static String clientIp(HttpServletRequest request) {
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
