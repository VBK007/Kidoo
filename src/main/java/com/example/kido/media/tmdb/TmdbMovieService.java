package com.example.kido.media.tmdb;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Service;

import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.metadata.FilenameParser;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fills in a video's poster, plot, rating and cast from TMDB when a scan found none —
 * the fields an {@code .nfo} sidecar or a local poster file would otherwise have
 * supplied.
 *
 * <p>Never overwrites a field that already has a value — callers are expected to check
 * that themselves (see {@code LibraryIngestService#backfillMetadata}), same convention
 * as {@code LibraryIngestService.backfillArtwork}'s {@code hasPoster()} guard.
 *
 * <p>{@link MediaType#FILM} and {@link MediaType#HOME_VIDEO} are looked up against
 * TMDB's movie catalog; {@link MediaType#SERIES} and {@link MediaType#ANIME} against
 * its TV catalog instead — a feature-length runtime check makes no sense for an
 * episodic file, so a TV search instead trusts TMDB's own top result outright, on the
 * strength of {@link com.example.kido.media.metadata.FilenameParser#seriesTitle}
 * already having narrowed the query down to just the show's name (see {@link
 * #pickVerifiedMatch}) — a raw episode filename only rarely carries a year to verify
 * against the way a movie release usually does.
 *
 * <p>A bare movie title search is not reliable enough to trust blindly: "Master" alone
 * matches dozens of unrelated films on TMDB, and title + release year still is not
 * always enough. For a movie, every candidate is verified against the file's own probed
 * runtime (already known from ffprobe, no extra local cost) before anything from it is
 * accepted — the one piece of ground truth this server has that TMDB's ranking cannot
 * see. A movie title with neither a known year nor a probed duration is judged too
 * ambiguous to guess at all, and is left alone rather than risk attaching the wrong
 * film's data.
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

    /**
     * 500px wide — clears {@code LibraryIngestService}'s own poster-floor check
     * (500x750) so a poster fetched from here is never immediately flagged as an
     * undersized thumbnail and replaced.
     */
    private static final String POSTER_IMAGE_SIZE = "w500";

    private final MediaProperties props;
    private final MediaPaths paths;
    private final TmdbRateLimiter rateLimiter;
    private final HttpClient http;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public TmdbMovieService(MediaProperties props, MediaPaths paths, TmdbRateLimiter rateLimiter) {
        this.props = props;
        this.paths = paths;
        this.rateLimiter = rateLimiter;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getCastPhotos().getTimeoutSeconds())))
                .build();
    }

    /** Which TMDB catalog a type is looked up against. */
    private enum Catalog {
        MOVIE, TV
    }

    private static Catalog catalogFor(MediaType type) {
        return type == MediaType.SERIES || type == MediaType.ANIME ? Catalog.TV : Catalog.MOVIE;
    }

    /**
     * TMDB's {@code original_language} for a movie search's top result, or null if
     * search finds nothing (or the lookup is disabled/unconfigured) — used by {@code
     * LibraryIngestService#guessMusicLanguage} to key a soundtrack album's language off
     * the film it belongs to, since a folder name only rarely spells the language out
     * directly the way an explicit "telugu"/"tamil" keyword would.
     *
     * <p>Deliberately not verified the way {@link #enrich} verifies a movie match: a
     * soundtrack album has no probed runtime to check a candidate against, so this
     * trusts TMDB's own relevance ranking on a bare title (optionally narrowed by year)
     * the same way a year-narrowed movie search in {@link #pickVerifiedMatch} already
     * does. The caller is expected to only accept a small, known set of language codes
     * back — an unrelated same-titled film in some third language is more likely to be
     * wrong than useful, and the caller's own fallback is the safer default in that case.
     */
    public String originalLanguageFor(String title, Integer year) {
        if (!props.getCastPhotos().isEnabled() || props.getCastPhotos().getApiKey().isBlank()
                || title == null || title.isBlank()) {
            return null;
        }
        try {
            JsonNode results = search(Catalog.MOVIE, title, year);
            if (results == null || results.isEmpty()) {
                return null;
            }
            String language = results.get(0).path("original_language").asString(null);
            return language == null || language.isBlank() ? null : language;
        } catch (Exception ex) {
            log.debug("Language lookup failed for '{}': {}", title, ex.getMessage());
            return null;
        }
    }

    /**
     * Looks up {@code item}'s title on TMDB and fills in whichever of poster, plot,
     * rating, cast and {@code tmdbId} it does not already have, from the best verified
     * match. Every field is additive: one already set is left exactly as it is, so this
     * is safe to call on an item that has some of these but not others.
     *
     * @return whether the item was changed
     */
    public boolean enrich(MediaItem item) {
        if (!props.getCastPhotos().isEnabled() || props.getCastPhotos().getApiKey().isBlank()) {
            return false;
        }
        try {
            Catalog catalog = catalogFor(item.getType());
            // An episode's title is not the show's. "[Anime Time] Black Lagoon - 029 -
            // Collateral Massacre" matches nothing on TMDB, while "Black Lagoon"
            // matches the series every episode of it belongs to — which is where the
            // poster, the story and the cast actually live.
            String query = catalog == Catalog.TV
                    ? FilenameParser.seriesTitle(item.getTitle())
                    : item.getTitle();
            JsonNode results = search(catalog, query, item.getYear());
            if (results == null) {
                return false;
            }
            MediaInfo info = item.getMediaInfo();
            Double durationSeconds = info == null ? null : info.getDurationSeconds();
            JsonNode match = pickVerifiedMatch(catalog, results, durationSeconds, item.getYear());
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
                String cast = fetchCast(catalog, tmdbId);
                if (cast != null) {
                    item.setCastMembers(cast);
                    changed = true;
                }
            }

            if (!item.hasPoster()) {
                String posterPath = match.path("poster_path").asString(null);
                if (posterPath != null && !posterPath.isBlank()) {
                    String stored = downloadPoster(item.getId(), posterPath);
                    if (stored != null) {
                        item.setPosterPath(stored);
                        changed = true;
                    }
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
    private String fetchCast(Catalog catalog, int tmdbId) throws IOException, InterruptedException {
        String url = "https://api.themoviedb.org/3/" + endpoint(catalog) + "/" + tmdbId
                + "/credits?api_key=" + props.getCastPhotos().getApiKey();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, props.getCastPhotos().getTimeoutSeconds())))
                .GET().build();
        rateLimiter.throttle();
        HttpResponse<String> response = sendWithHardTimeout(request, HttpResponse.BodyHandlers.ofString());
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
     * first whose official runtime is consistent with this file's probed duration.
     *
     * <p>TV has no such check available at all — a show's runtime is per-episode and
     * tells us nothing about the series as a whole — and unlike a movie title, a raw
     * filename rarely carries a year to narrow against either: {@link
     * com.example.kido.media.metadata.FilenameParser#seriesTitle} already did the only
     * verification available before the search was even made, by cutting the query down
     * to just the show's name. So a TV search's top result is trusted outright, the same
     * trust already given a year-narrowed movie search below.
     *
     * <p>With neither a duration nor a year, a movie search's top result is not trusted
     * at all — nothing here narrowed a bare title search the way {@code seriesTitle} did
     * for TV.
     */
    private JsonNode pickVerifiedMatch(Catalog catalog, JsonNode results, Double durationSeconds, Integer year)
            throws IOException, InterruptedException {
        if (catalog == Catalog.TV) {
            return results.isEmpty() ? null : results.get(0);
        }
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
        HttpResponse<String> response = sendWithHardTimeout(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        int runtime = mapper.readTree(response.body()).path("runtime").asInt(0);
        return runtime > 0 ? runtime : null;
    }

    private JsonNode search(Catalog catalog, String title, Integer year) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder("https://api.themoviedb.org/3/search/" + endpoint(catalog) + "?query=")
                .append(URLEncoder.encode(title, StandardCharsets.UTF_8))
                .append("&api_key=").append(props.getCastPhotos().getApiKey());
        if (year != null && year > 0) {
            // TMDB names this filter differently per catalog: a movie's is its release
            // year, a show's is the year it first aired.
            String yearParam = catalog == Catalog.MOVIE ? "year" : "first_air_date_year";
            url.append("&").append(yearParam).append("=").append(year);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(Math.max(1, props.getCastPhotos().getTimeoutSeconds())))
                .GET().build();
        rateLimiter.throttle();
        HttpResponse<String> response = sendWithHardTimeout(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("TMDB search returned " + response.statusCode());
        }
        JsonNode results = mapper.readTree(response.body()).path("results");
        return results.isArray() ? results : null;
    }

    private static String endpoint(Catalog catalog) {
        return catalog == Catalog.MOVIE ? "movie" : "tv";
    }

    /**
     * {@link HttpRequest.Builder#timeout} is supposed to bound a call on its own, but a
     * 2026-09-19/20 incident showed it doesn't always: a {@link #search} call sat
     * blocked for 12+ minutes with no error, freezing the whole scan thread behind it —
     * the same failure {@code TrackArtworkService} hit hours earlier on a different
     * host (iTunes, not TMDB), so this is evidently not specific to one upstream API.
     * Sending async and bounding the *wait* with {@code get(timeout, unit)} enforces the
     * deadline from outside the request, so this thread can never be held hostage by
     * whatever the request-level timeout misses, regardless of which of these four call
     * sites or which upstream host it happens on next.
     */
    private <T> HttpResponse<T> sendWithHardTimeout(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        long timeoutSeconds = Math.max(1, props.getCastPhotos().getTimeoutSeconds());
        var future = http.sendAsync(request, handler);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new IOException("TMDB request timed out after " + timeoutSeconds + "s", ex);
        } catch (ExecutionException ex) {
            throw new IOException("TMDB request failed", ex.getCause());
        }
    }

    /**
     * Downloads a poster from TMDB's image CDN into the same artwork cache directory
     * {@code TrackArtworkService} uses for music, keyed by item id so it survives a
     * rescan. Local sidecar/folder artwork ({@code LibraryIngestService
     * #backfillVideoArtwork}) always runs first and wins if it found anything — this is
     * only reached for an item that still has no poster at all, which is normal for a
     * library with no {@code poster.jpg} sitting next to every file (anime rips in
     * particular rarely carry one).
     */
    private String downloadPoster(String itemId, String posterPath) {
        try {
            String url = "https://image.tmdb.org/t/p/" + POSTER_IMAGE_SIZE + posterPath;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, props.getCastPhotos().getTimeoutSeconds())))
                    .GET().build();
            HttpResponse<byte[]> response = sendWithHardTimeout(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length == 0) {
                return null;
            }
            Path directory = paths.artworkDir().resolve(itemId);
            Files.createDirectories(directory);
            Path target = directory.resolve("poster.jpg");
            Files.write(target, response.body());
            return target.toString();
        } catch (Exception ex) {
            log.warn("TMDB poster download failed for item {}: {}", itemId, ex.getMessage());
            return null;
        }
    }
}
