package com.example.kido.mymirror;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import lombok.extern.slf4j.Slf4j;

/**
 * Carries a replayed Cloudflare {@code cf_clearance} (and the UA it is bound to) onto every mirror
 * upstream request, so the CF-gated backend lets the call through. See {@link MirrorAccessProperties}
 * for why this is needed and how the token is obtained.
 *
 * <p>It runs on the mymirror {@code RestTemplate} only, and is a no-op unless a {@code cf_clearance}
 * is configured — so with no configuration it changes nothing. A {@code cf_clearance} already present
 * in the request's own {@code Cookie} (e.g. one the client harvested) is left untouched.
 */
@Slf4j
public class CloudflareAccessInterceptor implements ClientHttpRequestInterceptor {

    private static final String CF_CLEARANCE = "cf_clearance=";

    private final MirrorAccessProperties access;

    public CloudflareAccessInterceptor(MirrorAccessProperties access) {
        this.access = access;
    }

    @Override
    @NonNull
    public ClientHttpResponse intercept(@NonNull HttpRequest request, @NonNull byte[] body,
                                        @NonNull ClientHttpRequestExecution execution) throws IOException {
        if (access.isEnabled()) {
            HttpHeaders headers = request.getHeaders();
            String cookie = headers.getFirst(HttpHeaders.COOKIE);
            boolean hasClearance = cookie != null && cookie.contains(CF_CLEARANCE);

            if (!hasClearance) {
                String token = clean(access.getCfClearance());
                if (token != null) {
                    headers.set(HttpHeaders.COOKIE,
                            cookie == null || cookie.isBlank() ? CF_CLEARANCE + token : cookie + "; " + CF_CLEARANCE + token);
                    hasClearance = true;
                }
            }

            // cf_clearance is bound to the UA that solved the challenge; pin it or Cloudflare rejects the replay.
            String ua = clean(access.getBrowserUserAgent());
            if (hasClearance && ua != null) {
                headers.set(HttpHeaders.USER_AGENT, ua);
            }
        }
        return execution.execute(request, body);
    }

    /** A configured value, trimmed, or {@code null} if blank or carrying a header-splitting CR/LF. */
    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0) {
            log.warn("Ignoring a mirror access value with an embedded line break");
            return null;
        }
        return trimmed;
    }
}
