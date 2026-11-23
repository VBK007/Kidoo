package com.example.kido.mymirror;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * A mirror that fails is a bad gateway, not a bug in this server. Ordered ahead of the
 * global handler, whose catch-all would otherwise answer 500 first.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.example.kido.mymirror")
public class MirrorErrorHandler {

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> upstreamFailed(RestClientException ex) {
        log.warn("Mirror request failed: {}", ex.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "The mirror did not answer properly");
    }

    /** A missing query/form parameter is the caller's mistake (400), not an upstream failure (500). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParameter(MissingServletRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, "Required parameter '" + ex.getParameterName() + "' is missing");
    }

    /** The route exists but not for this HTTP method: 405, not a catch-all 500. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "Method '" + ex.getMethod() + "' is not supported here");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
