package com.example.kido.mymirror;

import java.util.concurrent.Callable;

import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.example.kido.mymirror.model.ClientHeaders;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Carries the app user's own device headers upstream, so the site sees the caller's real
 * phone and language rather than one fixed browser. Every value is checked first: a missing,
 * oversized or illegal header falls back to a default instead of being forwarded.
 */
public final class ClientHeaderSupport {

    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 11; SM-A217) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    public static final String DEFAULT_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8";
    public static final String DEFAULT_ACCEPT_LANGUAGE = "en-US,en;q=0.9";

    private static final int MAX_HEADER_LENGTH = 512;
    private static final ClientHeaders NONE = new ClientHeaders(null, null, null);
    // Set on worker threads that act for a request but must not touch it: the servlet
    // container recycles a request once it completes, and a worker may outlive it.
    private static final ThreadLocal<ClientHeaders> OVERRIDE = new ThreadLocal<>();

    private ClientHeaderSupport() {
    }

    /** The headers of the request being served on this thread; all null off a request thread. */
    public static ClientHeaders current() {
        ClientHeaders override = OVERRIDE.get();
        if (override != null) return override;
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return NONE;
        }
        HttpServletRequest request = attrs.getRequest();
        return new ClientHeaders(
                request.getHeader(HttpHeaders.USER_AGENT),
                request.getHeader(HttpHeaders.ACCEPT),
                request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
    }

    /** Runs {@code task} with {@link #current()} answering {@code client}, off any request. */
    public static <T> T callAs(ClientHeaders client, Callable<T> task) throws Exception {
        OVERRIDE.set(client == null ? NONE : client);
        try {
            return task.call();
        } finally {
            OVERRIDE.remove();
        }
    }

    /**
     * Sets the caller's User-Agent and Accept-Language on an upstream request. Accept is
     * left alone: what the app accepts from us says nothing about what an XHR endpoint returns.
     */
    public static void apply(HttpHeaders headers, ClientHeaders client) {
        ClientHeaders c = client == null ? NONE : client;
        headers.set(HttpHeaders.USER_AGENT, orDefault(c.userAgent(), DEFAULT_USER_AGENT));
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, orDefault(c.acceptLanguage(), DEFAULT_ACCEPT_LANGUAGE));
    }

    /** The caller's value, unless it is missing, oversized or not a legal header value. */
    public static String orDefault(String value, String fallback) {
        if (value == null || value.isBlank() || value.length() > MAX_HEADER_LENGTH
                || value.chars().anyMatch(c -> (c < 0x20 && c != '\t') || c == 0x7f)) {
            return fallback;
        }
        return value.trim();
    }
}
