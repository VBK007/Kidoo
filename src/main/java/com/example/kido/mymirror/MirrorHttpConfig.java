package com.example.kido.mymirror;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
    public RestTemplate restTemplate(MirrorAccessProperties access) {
        RestTemplate restTemplate = new RestTemplate(timedFactory());
        restTemplate.getInterceptors().add(new CloudflareAccessInterceptor(access));
        return restTemplate;
    }

    /**
     * A second template for fetching from the mirror's segment CDN, a third party that is not
     * behind Cloudflare and gates its HLS variant playlists on the {@code Referer} alone. It
     * deliberately carries no interceptor, so neither the replayed {@code cf_clearance} (minted for
     * the mirror, not the CDN) nor the session cookie is ever sent off the mirror to the CDN; the
     * caller sets only the Referer the CDN checks. See {@code PlaybackRepository#fetchCdnPlaylist}.
     */
    @Bean("cdnRestTemplate")
    public RestTemplate cdnRestTemplate() {
        return new RestTemplate(timedFactory());
    }

    /** Timed out so a dead upstream cannot hang a request thread. */
    private static SimpleClientHttpRequestFactory timedFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(20));
        return factory;
    }
}
