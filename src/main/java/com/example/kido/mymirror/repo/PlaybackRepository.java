package com.example.kido.mymirror.repo;

import java.net.URI;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.UpstreamUrl;

@Repository
public class PlaybackRepository {
    private final RestTemplate restTemplate;

    public PlaybackRepository(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // Resolve stream via POST /mobile/p.php
    public String resolveStream(String baseUrl, String hash, String cookie) {
        HttpHeaders headers = mirrorHeaders(baseUrl, cookie);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("hash", hash);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(UpstreamUrl.of(baseUrl, "/mobile/p.php"), request, String.class);
    }

    // Alternate video endpoint GET /mobile/v.php
    public String altVideo(String baseUrl, String hash, String cookie) {
        return get(UpstreamUrl.of(baseUrl, "/mobile/v.php", "dp1", hash, "a", "2"), mirrorHeaders(baseUrl, cookie));
    }

    // Playlist for a title GET /mobile/playlist.php
    public String fetchPlaylist(String baseUrl, String id, String title, String t, String cookie) {
        URI url = UpstreamUrl.of(baseUrl, "/mobile/playlist.php", "id", id, "t", title, "tm", t);
        return get(url, mirrorHeaders(baseUrl, cookie));
    }

    /** The HLS master playlist's own URL; relative entries inside it resolve against this. */
    public URI hlsMasterUrl(String baseUrl, String id, String t) {
        return UpstreamUrl.of(baseUrl, "/mobile/hls/" + UpstreamUrl.segment(id) + ".m3u8",
                "in", t, "hd", "off", "lang", "eng", "hp", "yes");
    }

    // Fetch HLS master playlist
    public String fetchHlsMaster(String baseUrl, URI masterUrl, String cookie) {
        return get(masterUrl, mirrorHeaders(baseUrl, cookie));
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
