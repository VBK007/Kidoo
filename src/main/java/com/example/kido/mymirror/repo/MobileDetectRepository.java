package com.example.kido.mymirror.repo;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.model.ClientHeaders;

@Repository
public class MobileDetectRepository {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public String fetchHomePage(String appParam, ClientHeaders client) {
        URI uri = URI.create("https://net52.cc/mobile/home?app="
                + URLEncoder.encode(appParam, StandardCharsets.UTF_8));

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
