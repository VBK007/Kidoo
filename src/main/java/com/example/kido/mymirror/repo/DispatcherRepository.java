package com.example.kido.mymirror.repo;

import java.time.Duration;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestTemplate;

import com.example.kido.mymirror.DispatcherProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Asks the mirror pool for the backend config. Walks {@link DispatcherProperties#getMirrors() the
 * pool} in order and returns the first {@code /check.php} body that carries a {@code token_hash}; a
 * dead or blocked mirror (timeout, 403, non-JSON ad page) is skipped, not fatal. Returns {@code null}
 * when the whole pool is unreachable, which the caller turns into a 502.
 */
@Slf4j
@Repository
public class DispatcherRepository {

    private final DispatcherProperties props;
    private final RestTemplate restTemplate;

    @Autowired
    public DispatcherRepository(DispatcherProperties props) {
        this(props, shortTimeoutClient(props.getTimeout()));
    }

    /** Lets a caller (or a test) supply a {@link RestTemplate}, e.g. one bound to a mock server. */
    public DispatcherRepository(DispatcherProperties props, RestTemplate restTemplate) {
        this.props = props;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate shortTimeoutClient(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }

    /** The first usable {@code /check.php} body, or {@code null} if no mirror answered. */
    public String getDispatcherResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", props.getUserAgent());
        // The mirrors only answer requests that look like they came from the app.
        headers.set("X-Requested-With", "com.netmirror.app");
        HttpEntity<String> request = new HttpEntity<>(headers);

        for (String mirror : props.getMirrors()) {
            String url = stripTrailingSlash(mirror) + props.getCheckPath();
            try {
                String body = restTemplate.exchange(url, HttpMethod.GET, request, String.class).getBody();
                if (body != null && body.contains("token_hash")) {
                    log.debug("Dispatcher answered by {}", mirror);
                    return body;
                }
                log.debug("Mirror {} answered without a token_hash", mirror);
            } catch (RuntimeException e) {
                // A dead or blocked mirror is the normal case, not an error: try the next one.
                log.debug("Mirror {} unreachable: {}", mirror, e.getMessage());
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
