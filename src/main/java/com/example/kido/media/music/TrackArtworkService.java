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
import java.util.Locale;
import java.util.regex.Pattern;

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

    /**
     * Strips the site watermark these files are typically tagged with (e.g. {@code "-
     * MassTamilan.com"}, {@code "- Isaimini.Audio"}) before building a search query —
     * left in, it is the single biggest thing that would sink an otherwise-good match.
     */
    private static final Pattern SITE_TAG =
            Pattern.compile("\\s*[-|]\\s*[\\w]+\\.(com|in|fm|dev|io|fun|net|audio|so)\\s*$",
                    Pattern.CASE_INSENSITIVE);

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
        String query = searchQuery(item);
        if (query.isBlank()) {
            return null;
        }
        try {
            String artworkUrl = search(query);
            if (artworkUrl == null) {
                return null;
            }
            return download(item.getId(), artworkUrl);
        } catch (Exception ex) {
            // A network blip should not look different from "no match" to the caller;
            // both just mean try again never, since this is a best-effort fallback.
            log.debug("Music artwork lookup failed for '{}': {}", query, ex.getMessage());
            return null;
        }
    }

    private static String searchQuery(MediaItem item) {
        String artist = clean(item.getArtist());
        String title = clean(item.getTitle());
        if (title.isBlank()) {
            return "";
        }
        return artist.isBlank() ? title : artist + " " + title;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return SITE_TAG.matcher(value.trim()).replaceAll("").trim();
    }

    private String search(String query) throws IOException, InterruptedException {
        String url = "https://itunes.apple.com/search?media=music&entity=song&limit=1&term="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, props.getMusicArtwork().getTimeoutSeconds())))
                .GET().build();
        throttle();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
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
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length == 0) {
            throw new IOException("iTunes artwork download returned " + response.statusCode());
        }
        Path directory = paths.artworkDir().resolve(itemId);
        Files.createDirectories(directory);
        Path target = directory.resolve("poster.jpg");
        Files.write(target, response.body());
        return target.toString();
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
