package com.example.kido.mymirror.repo;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.MirrorCloudflare;
import com.example.kido.mymirror.MirrorRequestLimit;
import com.example.kido.mymirror.model.ClientHeaders;

@Repository
public class MobileDetectRepository {
    /**
     * The backend's rotating domains, tried in order. The upstream advertised net77.cc alongside
     * net52.cc (seen 2026-10-23), so a block on one host should fail over to the next rather than
     * taking the home feed down.
     */
    private static final List<String> HOSTS = List.of("https://net52.cc", "https://net77.cc");

    private final RestTemplate http;

    public MobileDetectRepository(@Qualifier("mirrorDiscoveryRestTemplate") RestTemplate http) {
        this.http = http;
    }

    public String fetchHomePage(String appParam, ClientHeaders client) {
        String app = URLEncoder.encode(appParam, StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        ClientHeaderSupport.apply(headers, client);
        headers.set("Referer", "https://mobiledetect.app/");
        headers.set("X-Requested-With", "com.netmirror.app");
        headers.set("Sec-Fetch-Site", "cross-site");
        headers.set("Sec-Fetch-Mode", "navigate");
        for (String host : HOSTS) {
            URI uri = URI.create(host + "/mobile/verify2.php?app=" + app);
            try {
                return http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
            } catch (HttpStatusCodeException ex) {
                if (MirrorCloudflare.isChallenge(ex) || ex.getStatusCode().value() == 403) {
                    throw new MirrorRequestLimit.AccessDenied();
                }
                if (ex.getStatusCode().is4xxClientError()) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "The upstream declined the request");
                }
            } catch (RestClientException ex) {
                // Try the remaining configured origin on a transport failure.
            }
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, "Upstream unreachable");
    }
}
