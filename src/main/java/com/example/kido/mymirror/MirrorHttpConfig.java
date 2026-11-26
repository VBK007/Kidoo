package com.example.kido.mymirror;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;

import com.example.kido.common.ApiException;
import org.springframework.web.client.RestTemplate;

/**
 * The shared upstream {@link RestTemplate}, in a configuration of its own so it carries no other
 * dependencies.
 *
 * <p>It used to live on {@link MyMirrorConfig}, but that class injects {@link MirrorSessionProvider}
 * for its cookie resolver, and the session provider needs {@code AdGateRepository}, which needs this
 * {@code RestTemplate} — a bean cycle (MyMirrorConfig &rarr; MirrorSessionProvider &rarr;
 * AdGateRepository &rarr; restTemplate &rarr; MyMirrorConfig) that stopped the whole context from
 * loading. Hosting the bean here breaks it: nothing this config needs leads back to the session
 * provider.
 */
@Configuration
public class MirrorHttpConfig {

    /**
     * Shared by the mymirror repositories. Timed out so a dead mirror cannot hang a request thread,
     * and fitted with the Cloudflare-access interceptor so a replayed {@code cf_clearance} rides every
     * upstream call (a no-op until one is configured — see {@link MirrorAccessProperties}).
     */
    @Bean
    @Primary
    public RestTemplate restTemplate(MirrorAccessProperties access, MirrorRequestLimit requestLimit,
                                     ObjectProvider<MirrorSessionProvider> sessions) {
        RestTemplate restTemplate = new RestTemplate(timedFactory());
        restTemplate.getInterceptors().add(requestLimit);
        restTemplate.getInterceptors().add((request, body, execution) -> {
            if (!access.isApproved(request.getURI())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Upstream origin is not approved");
            }
            return execution.execute(request, body);
        });
        restTemplate.getInterceptors().add((request, body, execution) -> {
            MirrorSessionProvider provider = sessions.getIfAvailable();
            String cookie = request.getHeaders().getFirst(HttpHeaders.COOKIE);
            if (provider == null || cookie == null) return execution.execute(request, body);
            long generation = provider.generation(cookie);
            try {
                var response = execution.execute(request, body);
                try {
                    int status = response.getStatusCode().value();
                    if (provider.confirmedExpiry(request.getURI(), status, response.getHeaders(), cookie, generation)) {
                        response.close();
                        throw new MirrorSessionProvider.Revoked();
                    }
                    if (!provider.recoveryEnabled() && status == access.getRevocationStatus() && status >= 400 && status < 500 && status != 429 && status != 403 && status != 412) {
                        response.close();
                        provider.noteRejected(cookie);
                        throw new MirrorSessionProvider.Revoked();
                    }
                    if ((status == 403 || status == 503)
                            && "challenge".equalsIgnoreCase(response.getHeaders().getFirst("cf-mitigated"))) {
                        provider.observe(cookie, MirrorSessionProvider.State.ACCESS_DENIED);
                    } else if (status == 429) {
                        provider.observe(cookie, MirrorSessionProvider.State.THROTTLED);
                    } else if (status >= 500) {
                        provider.observe(cookie, MirrorSessionProvider.State.TEMPORARILY_UNAVAILABLE);
                    } else if (status >= 200 && status < 300) {
                        provider.observe(cookie, MirrorSessionProvider.State.REACHABLE);
                    }
                    return response;
                } catch (java.io.IOException ex) {
                    response.close();
                    throw ex;
                }
            } catch (java.io.IOException ex) {
                provider.observe(cookie, MirrorSessionProvider.State.TEMPORARILY_UNAVAILABLE);
                throw ex;
            }
        });
        restTemplate.getInterceptors().add(new CloudflareAccessInterceptor(access));
        return restTemplate;
    }

    /**
     * A second template for fetching from the mirror's segment CDN, a third party that is not
     * behind Cloudflare and gates its HLS variant playlists on the {@code Referer} alone. It
     * does not carry the clearance interceptor, so neither the replayed {@code cf_clearance} (minted for
     * the mirror, not the CDN) nor the session cookie is ever sent off the mirror to the CDN; the
     * caller sets only the Referer the CDN checks. See {@code PlaybackRepository#fetchCdnPlaylist}.
     */
    @Bean("cdnRestTemplate")
    public RestTemplate cdnRestTemplate(MirrorRequestLimit requestLimit) {
        RestTemplate restTemplate = new RestTemplate(timedFactory());
        restTemplate.getInterceptors().add(requestLimit);
        stripCredentials(restTemplate);
        return restTemplate;
    }

    // A caller-selected subtitle origin must not be able to throttle mirror playback.
    @Bean("subtitleRestTemplate")
    public RestTemplate subtitleRestTemplate() {
        RestTemplate restTemplate = new RestTemplate(timedFactory());
        restTemplate.getInterceptors().add(new MirrorRequestLimit());
        stripCredentials(restTemplate);
        return restTemplate;
    }

    private static void stripCredentials(RestTemplate client) {
        client.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().remove(HttpHeaders.COOKIE);
            request.getHeaders().remove(HttpHeaders.AUTHORIZATION);
            request.getHeaders().remove(HttpHeaders.PROXY_AUTHORIZATION);
            return execution.execute(request, body);
        });
    }

    @Bean("mirrorDiscoveryRestTemplate")
    public RestTemplate mirrorDiscoveryRestTemplate(MirrorRequestLimit requestLimit, DispatcherProperties dispatcher) {
        RestTemplate client = new RestTemplate(timedFactory(dispatcher.getTimeout()));
        client.getInterceptors().add(requestLimit);
        stripCredentials(client);
        return client;
    }

    @Bean("mirrorRecoveryValidationClient")
    public RestTemplate mirrorRecoveryValidationClient(MirrorRequestLimit requestLimit) {
        RestTemplate client = new RestTemplate(timedFactory());
        client.getInterceptors().add(requestLimit);
        return client;
    }

    private static ClientHttpRequestFactory timedFactory() {
        return timedFactory(Duration.ofSeconds(20));
    }

    private static ClientHttpRequestFactory timedFactory(Duration deadline) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return (uri, method) -> {
            long remaining = MirrorRequestContext.deadlineWithin(deadline) - System.nanoTime();
            if (remaining <= 0) {
                throw new MirrorRequestLimit.DeadlineExceeded();
            }
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(Duration.ofMillis(Math.max(1, Duration.ofNanos(remaining).toMillis())));
            return factory.createRequest(uri, method);
        };
    }
}
