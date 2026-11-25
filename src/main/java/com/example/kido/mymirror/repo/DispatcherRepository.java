package com.example.kido.mymirror.repo;

import java.net.URI;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.example.kido.mymirror.DispatcherProperties;
import com.example.kido.mymirror.MirrorCloudflare;
import com.example.kido.mymirror.MirrorRequestLimit;

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

    public DispatcherRepository(DispatcherProperties props,
            @Qualifier("mirrorDiscoveryRestTemplate") RestTemplate restTemplate) {
        this.props = props;
        this.restTemplate = restTemplate;
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
                    log.debug("Dispatcher returned a discovery document");
                    return body;
                }
                log.debug("Dispatcher answered without a token hash");
            } catch (HttpStatusCodeException e) {
                if (MirrorCloudflare.isChallenge(e)) throw new MirrorRequestLimit.AccessDenied();
                log.debug("Mirror dispatcher returned status {}", e.getStatusCode().value());
            } catch (MirrorRequestLimit.Throttled | MirrorRequestLimit.Busy | MirrorRequestLimit.AccessDenied
                     | MirrorRequestLimit.DeadlineExceeded e) {
                throw e;
            } catch (RuntimeException e) {
                // A dead or blocked mirror is the normal case, not an error: try the next one.
                log.debug("Mirror dispatcher unreachable: {}", e.getClass().getSimpleName());
            }
        }
        return null;
    }

    public URI redirectLocation(URI uri) {
        ResponseEntity<Void> response = restTemplate.exchange(uri, HttpMethod.HEAD, HttpEntity.EMPTY, Void.class);
        URI location = response.getHeaders().getLocation();
        return response.getStatusCode().is3xxRedirection() && location != null ? uri.resolve(location) : null;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
