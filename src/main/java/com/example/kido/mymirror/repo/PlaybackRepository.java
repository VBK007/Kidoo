package com.example.kido.mymirror.repo;

import java.net.URI;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.MirrorOtt;
import com.example.kido.mymirror.UpstreamUrl;

@Repository
public class PlaybackRepository {
    private final RestTemplate restTemplate;
    private final RestTemplate cdnRestTemplate;

    public PlaybackRepository(RestTemplate restTemplate,
                              @Qualifier("cdnRestTemplate") RestTemplate cdnRestTemplate) {
        this.restTemplate = restTemplate;
        this.cdnRestTemplate = cdnRestTemplate;
    }

    // Playback keep-alive via POST /mobile/p.php. Returns {r:n}=ok, {r:y}=reload; it does NOT
    // resolve a stream (playlist.php is the stream source).
    public String heartbeat(String baseUrl, String hash, String cookie) {
        HttpHeaders headers = mirrorHeaders(baseUrl, cookie);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("hash", hash);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(UpstreamUrl.of(baseUrl, "/mobile/p.php"), request, String.class);
    }

    // Alternate video endpoint GET /v.php. Lives at the site root, not under /mobile.
    public String altVideo(String baseUrl, String hash, String cookie) {
        return get(UpstreamUrl.of(baseUrl, "/v.php", "dp1", hash, "a", "2"), mirrorHeaders(baseUrl, cookie));
    }

    // Streamtape resolver (alternate source) GET /streamtape.php, at the site root.
    public String streamtape(String baseUrl, String cookie) {
        return get(UpstreamUrl.of(baseUrl, "/streamtape.php"), mirrorHeaders(baseUrl, cookie));
    }

    // Playlist for a title GET /mobile/playlist.php
    // Prime Video titles play from /mobile/pv/playlist.php; the id's shape picks the catalogue.
    public String fetchPlaylist(String baseUrl, String id, String title, String t, String cookie) {
        String path = MirrorOtt.isPrime(id, cookie) ? "/mobile/pv/playlist.php" : "/mobile/playlist.php";
        URI url = UpstreamUrl.of(baseUrl, path, "id", id, "t", title, "tm", t);
        return get(url, mirrorHeaders(baseUrl, cookie));
    }

    // Fetch HLS master playlist. The URL (with its signed play_token) comes from the sources list
    // playlist.php returned, resolved on the mirror's host — see PlaybackService.hlsMaster.
    public String fetchHlsMaster(String baseUrl, URI masterUrl, String cookie) {
        return get(masterUrl, mirrorHeaders(baseUrl, cookie));
    }

    // Fetch an HLS variant playlist from the mirror's segment CDN. The CDN is a third party that is
    // not behind Cloudflare and gates the playlist on the mirror Referer alone, so this sends ONLY
    // that Referer and a browser UA/Accept-Language — no session cookie and no cf_clearance, which
    // would leak a mirror secret to an unrelated host. The template carries no interceptor for the
    // same reason. The segments the playlist names are public, so they are not fetched here: the
    // player is handed their absolute CDN URLs and streams them straight from the CDN.
    public String fetchCdnPlaylist(URI url, String referer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, ClientHeaderSupport.DEFAULT_USER_AGENT);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, ClientHeaderSupport.DEFAULT_ACCEPT_LANGUAGE);
        if (referer != null && !referer.isBlank()) {
            headers.set("Referer", referer);
        }
        return cdnRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
    }

    // Fetch subtitle file. The cookie goes only to the mirror itself, never to a third-party host.
    public String fetchSubtitle(URI subtitleUrl, String baseUrl, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        ClientHeaderSupport.apply(headers, ClientHeaderSupport.current());
        headers.set("X-Requested-With", "XMLHttpRequest");
        if (UpstreamUrl.sameHost(subtitleUrl, baseUrl)) headers.set("Cookie", cookie);
        return get(subtitleUrl, headers);
    }

    private static HttpHeaders mirrorHeaders(String baseUrl, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        ClientHeaderSupport.apply(headers, ClientHeaderSupport.current());
        headers.set("X-Requested-With", "XMLHttpRequest");
        headers.set("Referer", baseUrl + "/mobile/home?app=1");
        headers.set("Cookie", cookie);
        return headers;
    }

    private String get(URI url, HttpHeaders headers) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
    }
}
