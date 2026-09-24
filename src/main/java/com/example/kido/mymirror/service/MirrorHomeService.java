package com.example.kido.mymirror.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.example.kido.media.dto.HomeDtos.MirrorRailDto;
import com.example.kido.media.dto.HomeDtos.MirrorTileDto;
import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.MirrorHomeParser;
import com.example.kido.mymirror.MirrorHomeParser.Title;
import com.example.kido.mymirror.manager.TokenManager;
import com.example.kido.mymirror.model.ClientHeaders;
import com.example.kido.mymirror.repo.BrowseRepository;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mirror's home page joined with playlist.php, as one rail for the app's home screen.
 *
 * <p>The home screen must draw whether or not the mirror is up, so nothing here throws: any
 * failure means no rail. Playlists are fetched a few at a time within a fixed budget, and a
 * title whose playlist is late is sent without one rather than holding the screen up.
 */
@Slf4j
@Service
public class MirrorHomeService {
    static final int MAX_TILES = 12;
    private static final long BUDGET_MILLIS = 4000;
    private static final long CACHE_MILLIS = 5 * 60 * 1000;

    private final BrowseRepository browseRepository;
    private final PlaybackService playbackService;
    private final DispatcherService dispatcherService;
    private final TokenManager tokenManager;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    // A few at a time: twelve simultaneous calls per home screen would look like a flood upstream.
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "mirror-home");
        t.setDaemon(true);
        return t;
    });

    private record Cached(MirrorRailDto rail, long until) {}
    private final ConcurrentHashMap<String, Cached> byCookie = new ConcurrentHashMap<>();

    public MirrorHomeService(BrowseRepository browseRepository, PlaybackService playbackService,
                             DispatcherService dispatcherService, TokenManager tokenManager) {
        this.browseRepository = browseRepository;
        this.playbackService = playbackService;
        this.dispatcherService = dispatcherService;
        this.tokenManager = tokenManager;
    }

    public Optional<MirrorRailDto> rail(String cookie) {
        if (cookie == null || cookie.isBlank()) return Optional.empty();
        long now = System.currentTimeMillis();
        Cached cached = byCookie.get(cookie);
        if (cached != null && now < cached.until()) return Optional.of(cached.rail());
        try {
            MirrorRailDto rail = build(cookie);
            if (rail.items().isEmpty()) return Optional.empty();
            byCookie.values().removeIf(c -> c.until() <= now);
            byCookie.put(cookie, new Cached(rail, now + CACHE_MILLIS));
            return Optional.of(rail);
        } catch (RuntimeException e) {
            log.warn("Mirror rail left off the home screen: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private MirrorRailDto build(String cookie) {
        String baseUrl = dispatcherService.resolveActiveBaseUrl();
        // Fetched here, on the request thread, so the workers below find the tokens cached
        // and never race each other through the ad-gate.
        tokenManager.getT(cookie);

        List<Title> titles = MirrorHomeParser.parse(browseRepository.home(baseUrl, cookie), baseUrl);
        titles = titles.subList(0, Math.min(titles.size(), MAX_TILES));

        // The caller's device headers, copied now: a worker may still be running after the
        // request it serves has completed.
        ClientHeaders client = ClientHeaderSupport.current();
        List<Future<JsonNode>> playlists = new ArrayList<>();
        for (Title title : titles) {
            playlists.add(pool.submit(() -> ClientHeaderSupport.callAs(client, () ->
                    mapper.readTree(playbackService.playlist(title.id(), orEmpty(title.title()), cookie)))));
        }

        long deadline = System.currentTimeMillis() + BUDGET_MILLIS;
        List<MirrorTileDto> tiles = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            Title title = titles.get(i);
            tiles.add(new MirrorTileDto(title.id(), title.title(), title.image(),
                    await(playlists.get(i), deadline, title.id())));
        }
        return new MirrorRailDto("mirror", "From the mirror", tiles);
    }

    private static JsonNode await(Future<JsonNode> future, long deadline, String id) {
        try {
            return future.get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        } catch (Exception e) {
            future.cancel(true);
            log.debug("No playlist for mirror title {}: {}", id, e.getMessage());
            return null;
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
