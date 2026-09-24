package com.example.kido.mymirror.repo;

import java.net.URI;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestTemplate;

import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.UpstreamUrl;

@Repository
public class BrowseRepository {
    private final RestTemplate restTemplate;
    public BrowseRepository(RestTemplate restTemplate) { this.restTemplate = restTemplate; }

    public String getDetail(String baseUrl, String id, String t, String cookie) {
        return get(UpstreamUrl.of(baseUrl, "/mobile/post.php", "id", id, "t", t), cookie);
    }

    public String search(String baseUrl, String query, String t, String cookie) {
        return get(UpstreamUrl.of(baseUrl, "/mobile/search.php", "s", query, "t", t, "ADSearch", "false"), cookie);
    }

    public String episodes(String baseUrl, String id, String series, String t, String cookie) {
        return get(UpstreamUrl.of(baseUrl, "/mobile/episodes.php", "s", id, "series", series, "t", t, "page", "1"), cookie);
    }

    // The mirror's own home page, as the app user's session sees it.
    public String home(String baseUrl, String cookie) {
        return get(UpstreamUrl.of(baseUrl, "/mobile/home", "app", "1"), cookie);
    }

    private String get(URI url, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        ClientHeaderSupport.apply(headers, ClientHeaderSupport.current());
        headers.set("X-Requested-With", "XMLHttpRequest");
        headers.set("Cookie", cookie);
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
    }
}
