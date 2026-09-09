package com.example.kido.common;

import org.springframework.http.HttpStatus;

/** Simple domain exception mapped to an HTTP status by the global handler. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
