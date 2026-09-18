package com.example.kido.media.tmdb;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fills in a movie's plot, rating and cast from TMDB when a scan found none — the
 * three fields an {@code .nfo} sidecar would otherwise have supplied.
 *
 * <p>Never overwrites a field that already has a value — callers are expected to check
 * that themselves (see {@code LibraryIngestService#backfillMetadata}), same convention
 * as {@code LibraryIngestService.backfillArtwork}'s {@code hasPoster()} guard.
 *
 * <p>A bare title search is not reliable enough to trust blindly: "Master" alone
 * matches dozens of unrelated films on TMDB, and title + release year still is not
 * always enough. Every candidate is verified against the file's own probed runtime
 * (already known from ffprobe, no extra local cost) before anything from it is
 * accepted — the one piece of ground truth this server has that TMDB's ranking cannot
 * see. A title with neither a known year nor a probed duration is judged too ambiguous
 * to guess at all, and is left alone rather than risk attaching the wrong film's data.
 */
@Slf4j
@Service
public class TmdbMovieService {

    /** Candidates to check before giving up on an ambiguous title. */
    private static final int MAX_CANDIDATES = 5;

    /** Top-billed cast kept — this is a display list, not a full crew credit. */
    private static final int MAX_CAST_MEMBERS = 10;

    private static final int MAX_CAST_LENGTH = 4000;

    /**
     * How far a candidate's official runtime may differ from this file's probed
     * duration and still count as a match — a rip can trim or keep a differently-cut
     * intro/credits/post-credit scene, so this is deliberately generous, not a strict
     * equality check.
     */
    private static final int RUNTIME_TOLERANCE_MINUTES = 12;

    private final MediaProperties props;
    private final TmdbRateLimiter rateLimiter;
    private final HttpClient http;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public TmdbMovieService(MediaProperties props, TmdbRateLimiter rateLimiter) {
        this.props = props;
        this.rateLimiter = rateLimiter;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getCastPhotos().getTimeoutSeconds())))
                .build();
    }

    /**
     * Looks up {@code item}'s title on TMDB and fills in whichever of plot, rating,
     * cast and {@code tmdbId} it does not already have, from the best verified match.
     * Every field is additive: one already set is left exactly as it is, so this is
     * safe to call on an item that has some of these but not others.
     *
     * @return whether the item was changed
     */
    public boolean enrich(MediaItem item) {
        if (!props.getCastPhotos().isEnabled() || props.getCastPhotos().getApiKey().isBlank()) {
            return false;
        }
        try {
            JsonNode results = search(item.getTitle(), item.getYear());
            if (results == null) {
                return false;
            }
            MediaInfo info = item.getMediaInfo();
            Double durationSeconds = info == null ? null : info.getDurationSeconds();
            JsonNode match = pickVerifiedMatch(results, durationSeconds, item.getYear());
            if (match == null) {
                return false;
            }
            boolean changed = false;

            String overview = match.path("overview").asString(null);
            if ((item.getPlot() == null || item.getPlot().isBlank())
                    && overview != null && !overview.isBlank()) {
                item.setPlot(overview);
                changed = true;
            }

            // TMDB's own 0-10 community score — the closest thing to "the IMDB rating"
            // this server can fetch without a second, unrelated API/key.
            double voteAverage = match.path("vote_average").asDouble(0);
            if (item.getRating() == null && voteAverage > 0) {
                item.setRating(voteAverage);
                changed = true;
            }

            int tmdbId = match.path("id").asInt(0);
            if ((item.getTmdbId() == null || item.getTmdbId().isBlank()) && tmdbId > 0) {
                item.setTmdbId(String.valueOf(tmdbId));
                changed = true;
            }

            if ((item.getCastMembers() == null || item.getCastMembers().isBlank()) && tmdbId > 0) {
                String cast = fetchCast(tmdbId);
                if (cast != null) {
                    item.setCastMembers(cast);
                    changed = true;
                }
            }

            return changed;
        } catch (Exception ex) {
            // Not persisted either way: a network blip or an unmatched title should not
            // stop this item from being tried again on the next scan/backfill.
            log.warn("TMDB enrichment failed for '{}': {}", item.getTitle(), ex.getMessage());
            return false;
        }
    }

    /** Top-billed cast names, comma-joined the same way an {@code .nfo}'s would be. */
    private String fetchCast(int tmdbId) throws IOException, InterruptedException {
        String url = "https://api.themoviedb.org/3/movie/" + tmdbId
                + "/credits?api_key=" + props.getCastPhotos().getApiKey();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, props.getCastPhotos().getTimeoutSeconds())))
                .GET().build();
        rateLimiter.throttle();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode cast = mapper.readTree(response.body()).path("cast");
        if (!cast.isArray() || cast.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        int limit = Math.min(cast.size(), MAX_CAST_MEMBERS);
        for (int i = 0; i < limit; i++) {
            String name = cast.get(i).path("name").asString(null);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return null;
        }
        String joined = String.join(", ", names);
        return joined.length() <= MAX_CAST_LENGTH ? joined : joined.substring(0, MAX_CAST_LENGTH);
    }

    /**
     * Walks the top few search results, in TMDB's own relevance order, and returns the
     * first whose official runtime is consistent with this file's probed duration. With
     * no probed duration to check against, a year-narrowed search's top result is
     * trusted as-is; with neither signal, nothing is trusted.
     */
    private JsonNode pickVerifiedMatch(JsonNode results, Double durationSeconds, Integer year)
            throws IOException, InterruptedException {
        if (durationSeconds == null || durationSeconds <= 0) {
            boolean yearNarrowed = year != null && year > 0;
            return yearNarrowed && !results.isEmpty() ? results.get(0) : null;
        }
        double expectedMinutes = durationSeconds / 60.0;
        int limit = Math.min(results.size(), MAX_CANDIDATES);
        for (int i = 0; i < limit; i++) {
            JsonNode candidate = results.get(i);
            Integer runtime = fetchRuntimeMinutes(candidate.path("id").asInt(0));
            if (runtime != null && Math.abs(expectedMinutes - runtime) <= RUNTIME_TOLERANCE_MINUTES) {
                return candidate;
            }
        }
        return null;
    }

    private Integer fetchRuntimeMinutes(int tmdbId) throws IOException, InterruptedException {
        if (tmdbId <= 0) {
            return null;
        }
        String url = "https://api.themoviedb.org/3/movie/" + tmdbId
                + "?api_key=" + props.getCastPhotos().getApiKey();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, props.getCastPhotos().getTimeoutSeconds())))
                .GET().build();
        rateLimiter.throttle();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        int runtime = mapper.readTree(response.body()).path("runtime").asInt(0);
        return runtime > 0 ? runtime : null;
    }

    private JsonNode search(String title, Integer year) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder("https://api.themoviedb.org/3/search/movie?query=")
                .append(URLEncoder.encode(title, StandardCharsets.UTF_8))
                .append("&api_key=").append(props.getCastPhotos().getApiKey());
        if (year != null && year > 0) {
            url.append("&year=").append(year);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(Math.max(1, props.getCastPhotos().getTimeoutSeconds())))
                .GET().build();
        rateLimiter.throttle();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("TMDB search returned " + response.statusCode());
        }
        JsonNode results = mapper.readTree(response.body()).path("results");
        return results.isArray() ? results : null;
    }
}
