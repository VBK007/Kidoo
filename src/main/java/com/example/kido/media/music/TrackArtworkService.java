package com.example.kido.media.music;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Service;

import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaItem;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fallback album art for a track whose file carries no cover of its own, looked up
 * against Apple's iTunes Search API.
 *
 * <p>Chosen over the alternatives for needing no API key or registered app (unlike
 * Spotify), and for having solid coverage of commercially released film music, which is
 * most of what a home library actually holds — unlike MusicBrainz/Cover Art Archive,
 * which indexes by release and is far patchier for Indian film soundtracks.
 *
 * <p>Fetch-on-demand from {@link com.example.kido.media.library.LibraryIngestService
 * #backfillArtwork}, at most once per track ever: the caller stamps {@link
 * MediaItem#getMusicArtworkCheckedAt()} regardless of outcome, so a track this API
 * simply has no match for is not re-queried on every future scan.
 */
@Slf4j
@Service
public class TrackArtworkService {

    private final MediaProperties props;
    private final MediaPaths paths;
    private final HttpClient http;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private long lastCallAt = 0;

    public TrackArtworkService(MediaProperties props, MediaPaths paths) {
        this.props = props;
        this.paths = paths;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getMusicArtwork().getTimeoutSeconds())))
                .build();
    }

    /**
     * Looks up and stores artwork for one track.
     *
     * @return the absolute path written, or null if disabled, unmatched, or the
     *         download failed — the caller stamps {@code musicArtworkCheckedAt} either way
     */
    public String fetchAndStore(MediaItem item) {
        if (!props.getMusicArtwork().isEnabled()) {
            return null;
        }
        for (String query : searchQueries(item)) {
            try {
                String artworkUrl = search(query);
                if (artworkUrl != null) {
                    return download(item.getId(), artworkUrl);
                }
            } catch (Exception ex) {
                // A network blip should not look different from "no match" to the
                // caller; both just mean move to the next candidate query, if any.
                log.debug("Music artwork lookup failed for '{}': {}", query, ex.getMessage());
            }
        }
        return null;
    }

    /**
     * Every field on these files came from a folder guess or an ID3 tag written by
     * whichever download site handed it out, so one query is often not enough: a
     * credits-list artist ({@code "A, B, C"}) or a site-watermarked title can each
     * individually sink an otherwise-findable match. Tried in order, most specific
     * first, stopping at the first hit — most tracks resolve on the first query, so
     * this only costs extra throttled calls on the ones that would otherwise have
     * gone unmatched entirely.
     */
    private static List<String> searchQueries(MediaItem item) {
        String artist = clean(item.getArtist());
        String title = clean(item.getTitle());
        if (title.isBlank()) {
            return List.of();
        }
        Set<String> queries = new LinkedHashSet<>();
        if (!artist.isBlank()) {
            queries.add(artist + " " + title);
            String firstArtist = artist.split("\\s*,\\s*")[0];
            if (!firstArtist.equals(artist)) {
                queries.add(firstArtist + " " + title);
            }
        }
        queries.add(title);
        return List.copyOf(queries);
    }

    private static String clean(String value) {
        String cleaned = SiteWatermark.clean(value);
        return cleaned == null ? "" : cleaned;
    }

    private String search(String query) throws IOException, InterruptedException {
        String url = "https://itunes.apple.com/search?media=music&entity=song&limit=1&term="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, props.getMusicArtwork().getTimeoutSeconds())))
                .GET().build();
        throttle();
        HttpResponse<String> response = sendWithHardTimeout(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("iTunes search returned " + response.statusCode());
        }
        JsonNode results = mapper.readTree(response.body()).path("results");
        if (!results.isArray() || results.isEmpty()) {
            return null;
        }
        String artwork = results.get(0).path("artworkUrl100").asString(null);
        if (artwork == null || artwork.isBlank()) {
            return null;
        }
        // The only documented way to get a larger image from this API: the size is
        // baked into the filename, and swapping it needs no extra request.
        return artwork.replace("100x100bb", "600x600bb");
    }

    private String download(String itemId, String artworkUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(artworkUrl))
                .timeout(Duration.ofSeconds(Math.max(1, props.getMusicArtwork().getTimeoutSeconds())))
                .GET().build();
        HttpResponse<byte[]> response = sendWithHardTimeout(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length == 0) {
            throw new IOException("iTunes artwork download returned " + response.statusCode());
        }
        Path directory = paths.artworkDir().resolve(itemId);
        Files.createDirectories(directory);
        Path target = directory.resolve("poster.jpg");
        Files.write(target, response.body());
        return target.toString();
    }

    /**
     * {@link HttpRequest.Builder#timeout} is supposed to bound this on its own, but a
     * 2026-09-19 incident showed it doesn't always: one call to this API sat blocked for
     * over 13 minutes with no error, freezing the whole scan thread behind it (this is
     * the only network call on that thread) — the request itself was fine, confirmed by
     * a plain curl seconds later. Sending async and bounding the *wait* with {@code
     * get(timeout, unit)} enforces the deadline from outside the request, so this
     * thread can never again be held hostage by whatever the request-level timeout
     * missed.
     */
    private <T> HttpResponse<T> sendWithHardTimeout(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        long timeoutSeconds = Math.max(1, props.getMusicArtwork().getTimeoutSeconds());
        var future = http.sendAsync(request, handler);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new IOException("iTunes request timed out after " + timeoutSeconds + "s", ex);
        } catch (ExecutionException ex) {
            throw new IOException("iTunes request failed", ex.getCause());
        }
    }

    /** Only the search call is throttled — the artwork download hits Apple's CDN, not the API. */
    private synchronized void throttle() {
        long minIntervalMs = Math.max(0, props.getMusicArtwork().getMinRequestIntervalMs());
        long wait = lastCallAt + minIntervalMs - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallAt = System.currentTimeMillis();
    }
}
