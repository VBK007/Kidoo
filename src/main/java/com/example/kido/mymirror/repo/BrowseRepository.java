package com.example.kido.mymirror.repo;

import java.net.URI;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestTemplate;

import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.MirrorOtt;
import com.example.kido.mymirror.UpstreamUrl;

@Repository
public class BrowseRepository {
    private final RestTemplate restTemplate;
    public BrowseRepository(RestTemplate restTemplate) { this.restTemplate = restTemplate; }

    // Prime Video detail answers only at /pv/post.php; /mobile/pv/post.php says "Invalid User".
    // Hotstar detail answers at /mobile/hs/post.php (the shared /mobile/post.php returns the wrong
    // catalogue for a Hotstar id). Verified live 2026-11-23.
    public String getDetail(String baseUrl, String id, String t, String cookie) {
        if (MirrorOtt.isPrime(id, cookie)) return get(UpstreamUrl.of(baseUrl, "/pv/post.php", "id", id, "t", t), cookie);
        String path = MirrorOtt.isHotstar(id, cookie) ? "/mobile/hs/post.php" : "/mobile/post.php";
        return get(UpstreamUrl.of(baseUrl, path, "id", id, "t", t), cookie);
    }

    public String search(String baseUrl, String query, String t, String cookie) {
        if (MirrorOtt.isPrime(null, cookie)) {
            return get(UpstreamUrl.of(baseUrl, "/mobile/pv/search.php", "s", query, "t", t), cookie);
        }
        if (MirrorOtt.isHotstar(null, cookie)) {
            return get(UpstreamUrl.of(baseUrl, "/mobile/hs/search.php", "s", query, "t", t), cookie);
        }
        return get(UpstreamUrl.of(baseUrl, "/mobile/search.php", "s", query, "t", t, "ADSearch", "false"), cookie);
    }

    // id is the season's id, series the title's; the title decides the catalogue. Hotstar episodes
    // answer at /mobile/hs/episodes.php — the shared /mobile/episodes.php returns only a pagination
    // stub for a Hotstar id (wrong catalogue). Verified live 2026-11-23.
    public String episodes(String baseUrl, String id, String series, String t, String cookie) {
        if (MirrorOtt.isPrime(series, cookie)) {
            return get(UpstreamUrl.of(baseUrl, "/mobile/pv/episodes.php", "s", id, "series", series, "t", t, "page", "1"), cookie);
        }
        String path = MirrorOtt.isHotstar(series, cookie) ? "/mobile/hs/episodes.php" : "/mobile/episodes.php";
        return get(UpstreamUrl.of(baseUrl, path, "s", id, "series", series, "t", t, "page", "1"), cookie);
    }

    // Trailer sources for a title. Lives at the site root, not under /mobile.
    public String trailer(String baseUrl, String id, String cookie) {
        return get(UpstreamUrl.of(baseUrl, "/trailer.php", "id", id), cookie);
    }

    // The home catalogue, categorised, in each OTT's own shape. Prime has a JSON homepage at
    // /pv/homepage.php: {"slider":[{id,...}],"post":[{"ids":"a,b,..","cate":".."}]}. Netflix's old
    // server-rendered /mobile/home?app=1 was RETIRED upstream (it now answers a 24-byte router 404
    // under every variant, even after a /mobile/setting.php ott POST — confirmed on the live mirror
    // 2026-11-22). Hotstar/dp has no home or trending route at all (every /mobile/hs home candidate
    // 404s — confirmed 2026-11-23). So Netflix AND Hotstar homes are both COMPOSED by MirrorHomeService
    // (from trending()/seed searches + post.php suggest graphs) and never reach here; this path now
    // serves only Prime. No t is needed. MirrorHomeParser.parseHome sniffs the HTML vs JSON shape for
    // whatever this returns.
    public String homeFeed(String baseUrl, String cookie) {
        if (MirrorOtt.isPrime(null, cookie)) return get(UpstreamUrl.of(baseUrl, "/pv/homepage.php"), cookie);
        return get(UpstreamUrl.of(baseUrl, "/mobile/home", "app", "1"), cookie);
    }

    // The mirror's "Top Searches" list: /mobile/search.php with no query. A 20-item JSON
    // {"status":"y","head":"Top Searches","searchResult":[{"id","t"}]}, each item carrying its title
    // name (unlike post.php suggest ids). It is the Netflix catalogue regardless of the ott cookie
    // (verified nf/pv/dp return the identical numeric-id list, 2026-11-22), so it seeds the composed
    // Netflix home only. No t is needed.
    public String trending(String baseUrl, String cookie) {
        return get(UpstreamUrl.of(baseUrl, "/mobile/search.php"), cookie);
    }

    private String get(URI url, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        ClientHeaderSupport.apply(headers, ClientHeaderSupport.current());
        headers.set("X-Requested-With", "XMLHttpRequest");
        headers.set("Cookie", cookie);
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
    }
}
