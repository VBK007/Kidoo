package com.example.kido.mymirror.repo;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Repository;
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

    public String verifyAds(String baseUrl, String cookie, ClientHeaders client) {
        String url = baseUrl + "/mobile/verify2.php";
        HttpHeaders headers = new HttpHeaders();
        ClientHeaderSupport.apply(headers, client);
        headers.set("X-Requested-With", "XMLHttpRequest");
        headers.set("Cookie", cookie);

        HttpEntity<String> request = new HttpEntity<>(headers);
        return restTemplate.exchange(url, HttpMethod.GET, request, String.class).getBody();
    }

}
