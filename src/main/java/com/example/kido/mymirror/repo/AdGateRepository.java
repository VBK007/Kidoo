package com.example.kido.mymirror.repo;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.model.ClientHeaders;

@Repository
public class AdGateRepository {
    private final RestTemplate restTemplate;
    public AdGateRepository(RestTemplate restTemplate) { this.restTemplate = restTemplate; }

    public String verifyAds(String baseUrl, String cookie) {
        return verifyAds(baseUrl, cookie, ClientHeaderSupport.current());
    }

    /**
     * Polls the mirror's ad-gate. Verified against the live app (net52.cc): the home page POSTs
     * {@code verify=<token>} as {@code application/x-www-form-urlencoded} to {@code verify2.php},
     * where the token is the decoded value of the {@code addhash} cookie the home page set — the
     * same value it later hands the ad-redirect as {@code ffr455}. While the gate is uncleared the
     * mirror answers {@code {"c":"n","statusup":"Waiting for your ads click."}}; a real ad click on
     * the device flips it to {@code "statusup":"OK"}.
     */
    public String verifyAds(String baseUrl, String cookie, ClientHeaders client) {
        String url = baseUrl + "/mobile/verify2.php";
        HttpHeaders headers = new HttpHeaders();
        ClientHeaderSupport.apply(headers, client);
        headers.set("X-Requested-With", "XMLHttpRequest");
        headers.set("Referer", baseUrl + "/mobile/home?app=1");
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Cookie", cookie);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("verify", verifyToken(cookie));
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
        return restTemplate.exchange(url, HttpMethod.POST, request, String.class).getBody();
    }

    /**
     * The ad-gate token the mirror expects in the {@code verify} field: the {@code addhash} cookie's
     * value, URL-decoded so the form serializer re-encodes its {@code ::} separators exactly once.
     * Falls back to the empty string when the session carries no {@code addhash} yet.
     */
    static String verifyToken(String cookie) {
        if (cookie == null) return "";
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("addhash=")) {
                return URLDecoder.decode(p.substring("addhash=".length()), StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
