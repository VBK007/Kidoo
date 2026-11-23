package com.example.kido.mymirror.repo;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.model.ClientHeaders;

@Repository
public class MobileDetectRepository {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * The backend's rotating domains, tried in order. The upstream advertised net77.cc alongside
     * net52.cc (seen 2026-10-23), so a block on one host should fail over to the next rather than
     * taking the home feed down.
     */
    private static final List<String> HOSTS = List.of("https://net52.cc", "https://net77.cc");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public String fetchHomePage(String appParam, ClientHeaders client) {
        String app = URLEncoder.encode(appParam, StandardCharsets.UTF_8);
        RuntimeException last = null;
        for (String host : HOSTS) {
            try {
                return fetchHomePage(host, app, client);
            } catch (ApiException ex) {
                last = ex; // this host is blocked/unreachable — try the next one
            }
        }
        throw last != null ? last
                : new ApiException(HttpStatus.BAD_GATEWAY, "No upstream host configured");
    }

    private String fetchHomePage(String host, String app, ClientHeaders client) {
        // /mobile/home?app=1 was retired upstream (404, seen 2026-10-23). verify2.php is the live
        // entry page; the app context still rides along as a query param the PHP page ignores.
        URI uri = URI.create(host + "/mobile/verify2.php?app=" + app);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", ClientHeaderSupport.orDefault(client.userAgent(), ClientHeaderSupport.DEFAULT_USER_AGENT))
                .header("Accept", ClientHeaderSupport.orDefault(client.accept(), ClientHeaderSupport.DEFAULT_ACCEPT))
                .header("Accept-Language", ClientHeaderSupport.orDefault(client.acceptLanguage(), ClientHeaderSupport.DEFAULT_ACCEPT_LANGUAGE))
                .header("Referer", "https://mobiledetect.app/")
                .header("X-Requested-With", "com.netmirror.app")
                .header("Sec-Fetch-Site", "cross-site")
                .header("Sec-Fetch-Mode", "navigate")
                .GET()
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Upstream returned " + response.statusCode());
            }
            return response.body(); // raw HTML
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Upstream unreachable: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while fetching upstream");
        }
    }
}
