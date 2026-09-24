package com.example.kido.mymirror.repo;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestTemplate;

@Repository
public class DispatcherRepository {
    private final RestTemplate restTemplate;
    public DispatcherRepository(RestTemplate restTemplate) { this.restTemplate = restTemplate; }

    public String getDispatcherResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "okhttp/4.9.2");
        HttpEntity<String> request = new HttpEntity<>(headers);
        return restTemplate.exchange("https://mobiledetect.app/check.php", HttpMethod.GET, request, String.class).getBody();
    }
}
