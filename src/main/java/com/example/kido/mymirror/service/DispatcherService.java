package com.example.kido.mymirror.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.example.kido.common.ApiException;
import com.example.kido.mymirror.repo.DispatcherRepository;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
public class DispatcherService {
    // Every mirror call needs the base URL, and finding it costs a check.php call plus a HEAD
    // per mirror; a mirror that was up a few minutes ago is almost always still up.
    private static final long CACHE_MILLIS = 5 * 60 * 1000;

    private final DispatcherRepository repo;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private volatile String cachedUrl;
    private volatile long cachedUntil;

    public DispatcherService(DispatcherRepository repo, RestTemplate restTemplate) {
        this.repo = repo;
        this.restTemplate = restTemplate;
    }

    /** The first reachable mirror, without a trailing slash. Throws 502 when none answers. */
    public String resolveActiveBaseUrl() {
        String url = cachedUrl;
        if (url != null && System.currentTimeMillis() < cachedUntil) return url;
        synchronized (this) {
            if (cachedUrl != null && System.currentTimeMillis() < cachedUntil) return cachedUrl;
            String resolved = resolveFromDispatcher();
            cachedUrl = resolved;
            cachedUntil = System.currentTimeMillis() + CACHE_MILLIS;
            return resolved;
        }
    }

    private String resolveFromDispatcher() {
        List<String> candidates = new ArrayList<>();
        try {
            JsonNode node = mapper.readTree(repo.getDispatcherResponse());
            String tokenHash = node.path("token_hash").asString(null);
            if (tokenHash != null) candidates.add(decode(tokenHash));
            for (JsonNode u : node.path("u")) candidates.add(decode(u.asString()));
        } catch (RuntimeException e) {
            log.warn("Dispatcher response unreadable: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The mirror dispatcher did not answer properly");
        }
        for (String candidate : candidates) {
            String base = stripTrailingSlash(candidate);
            if (isUrlActive(base)) return base;
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, "No mirror is reachable right now");
    }

    private static String decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8).trim();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private boolean isUrlActive(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        try {
            return alive(restTemplate.exchange(url, HttpMethod.HEAD, null, String.class).getStatusCode());
        } catch (HttpStatusCodeException e) {
            return alive(e.getStatusCode());
        } catch (Exception e) {
            return false;
        }
    }

    // HEAD is not redirected by the client, so a mirror answering 301 to its home page is up;
    // so is one that answers 405 because it will not do HEAD at all.
    private static boolean alive(HttpStatusCode status) {
        return status.is2xxSuccessful() || status.is3xxRedirection() || status.value() == 405;
    }
}
