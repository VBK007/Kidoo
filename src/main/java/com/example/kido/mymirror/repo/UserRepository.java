package com.example.kido.mymirror.repo;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.example.kido.mymirror.ClientHeaderSupport;

@Repository("mirrorUserRepository")
public class UserRepository {
    private final RestTemplate restTemplate;
    public UserRepository(RestTemplate restTemplate) { this.restTemplate = restTemplate; }

    public String addToWatchlist(String baseUrl, String id, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ClientHeaderSupport.apply(headers, ClientHeaderSupport.current());
        headers.set("Cookie", cookie);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("id", id);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(baseUrl + "/mobile/MyWatchList.php", request, String.class);
    }

    public String recentPlay(String baseUrl, String cookie) {
        return get(baseUrl + "/mobile/recentplay.php", cookie);
    }

    public String settings(String baseUrl, String cookie) {
        return get(baseUrl + "/mobile/setting.php", cookie);
    }

    private String get(String url, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        ClientHeaderSupport.apply(headers, ClientHeaderSupport.current());
        headers.set("Cookie", cookie);
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
    }
}
