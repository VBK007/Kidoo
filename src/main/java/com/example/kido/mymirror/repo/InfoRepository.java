package com.example.kido.mymirror.repo;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestTemplate;

import com.example.kido.mymirror.ClientHeaderSupport;

@Repository
public class InfoRepository {
    private final RestTemplate restTemplate;
    public InfoRepository(RestTemplate restTemplate) { this.restTemplate = restTemplate; }

    public String appInfo(String baseUrl, String cookie) {
        String url = baseUrl + "/mobile/app-info.php";
        HttpHeaders headers = new HttpHeaders();
        ClientHeaderSupport.apply(headers, ClientHeaderSupport.current());
        headers.set("Cookie", cookie);

        HttpEntity<String> request = new HttpEntity<>(headers);
        return restTemplate.exchange(url, HttpMethod.GET, request, String.class).getBody();
    }
}
