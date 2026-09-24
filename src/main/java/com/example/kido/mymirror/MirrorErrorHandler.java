package com.example.kido.mymirror;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.BAD_GATEWAY.value());
        body.put("error", HttpStatus.BAD_GATEWAY.getReasonPhrase());
        body.put("message", "The mirror did not answer properly");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }
}
