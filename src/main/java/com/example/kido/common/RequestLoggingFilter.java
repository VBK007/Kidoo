package com.example.kido.common;

import java.io.IOException;
import java.util.regex.Pattern;

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

    // Query parameters that carry a credential: the watch party socket's JWT, and a mirror
    // session cookie from callers that send it on the URL rather than in a header.
    private static final Pattern SECRET_PARAM = Pattern.compile("(^|&)(token|cookie)=[^&]*", Pattern.CASE_INSENSITIVE);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long took = System.currentTimeMillis() - start;
            String query = request.getQueryString() == null ? ""
                    : "?" + SECRET_PARAM.matcher(request.getQueryString()).replaceAll("$1$2=***");
            log.info("{} {} {}{} -> {} ({} ms)",
                    ClientAddress.resolve(request), request.getMethod(), request.getRequestURI(), query, response.getStatus(), took);
        }
    }
}
