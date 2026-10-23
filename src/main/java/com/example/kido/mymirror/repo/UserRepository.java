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
        return postForm(baseUrl, "/mobile/MyWatchList.php", "id", id, cookie);
    }

    /** List the session's recent plays. GET /mobile/recentplay.php. */
    public String recentPlay(String baseUrl, String cookie) {
        return get(baseUrl + "/mobile/recentplay.php", cookie);
    }

    /** Record a title as recently played. POST /mobile/recentplay.php form {@code recentplay=<id>}. */
    public String addRecentPlay(String baseUrl, String id, String cookie) {
        return postForm(baseUrl, "/mobile/recentplay.php", "recentplay", id, cookie);
    }

    /** Remove a title from recent plays. POST /mobile/recentplay.php form {@code deleteid=<id>}. */
    public String deleteRecentPlay(String baseUrl, String id, String cookie) {
        return postForm(baseUrl, "/mobile/recentplay.php", "deleteid", id, cookie);
    }

    /** Read the session's settings. GET /mobile/setting.php. */
    public String settings(String baseUrl, String cookie) {
        return get(baseUrl + "/mobile/setting.php", cookie);
    }

    /** Toggle HD. POST /mobile/setting.php form {@code hd=<value>}. */
    public String setHd(String baseUrl, String value, String cookie) {
        return postForm(baseUrl, "/mobile/setting.php", "hd", value, cookie);
    }

    /** Switch the OTT skin/catalog (nf/pv/hs). POST /mobile/setting.php form {@code ott=<value>}. */
    public String setOtt(String baseUrl, String value, String cookie) {
        return postForm(baseUrl, "/mobile/setting.php", "ott", value, cookie);
    }

    /** Set the preferred language. POST /mobile/language.php form {@code lang=<value>}. */
    public String setLanguage(String baseUrl, String value, String cookie) {
        return postForm(baseUrl, "/mobile/language.php", "lang", value, cookie);
    }

    private String postForm(String baseUrl, String path, String field, String value, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ClientHeaderSupport.apply(headers, ClientHeaderSupport.current());
        headers.set("X-Requested-With", "XMLHttpRequest");
        headers.set("Referer", baseUrl + "/mobile/home?app=1");
        headers.set("Cookie", cookie);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add(field, value);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(baseUrl + path, request, String.class);
    }

    private String get(String url, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        ClientHeaderSupport.apply(headers, ClientHeaderSupport.current());
        headers.set("Cookie", cookie);
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
    }
}
