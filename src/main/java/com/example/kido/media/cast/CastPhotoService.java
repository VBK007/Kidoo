package com.example.kido.media.cast;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.tmdb.TmdbRateLimiter;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Looks up and caches cast/crew photos from TMDB, an open, free-for-personal-use movie
 * database — the only outbound integration this server makes for metadata.
 *
 * <p>Fetch-on-demand rather than a background job: a single TMDB search plus a small
 * image download normally finishes in well under a second, far faster than the ffmpeg
 * work {@code TeaserClipService}/{@code DownloadService} queue — so a synchronous call
 * on the request thread, cached in {@link CastPhotoRepository} thereafter, is simpler
 * and sufficient. Every name is looked up at most once ever.
 *
 * <p>Dormant with no outbound calls at all when {@link MediaProperties.CastPhotos#getApiKey()}
 * is blank, matching how the media library itself stays inert with no roots configured.
 */
@Slf4j
@Service
public class CastPhotoService {

    private final MediaProperties props;
    private final CastPhotoRepository photos;
    private final TmdbRateLimiter rateLimiter;
    private final HttpClient http;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private Path cacheRoot;

    public CastPhotoService(MediaProperties props, CastPhotoRepository photos, TmdbRateLimiter rateLimiter) {
        this.props = props;
        this.photos = photos;
        this.rateLimiter = rateLimiter;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getCastPhotos().getTimeoutSeconds())))
                .build();
    }

    @PostConstruct
    void init() {
        cacheRoot = Path.of(props.getCastPhotos().getCacheDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(cacheRoot);
        } catch (IOException ex) {
            log.warn("Could not create cast photos dir {}: {}", cacheRoot, ex.getMessage());
        }
        log.info("Cast photos at {} (enabled={}, TMDB key configured={})",
                cacheRoot, props.getCastPhotos().isEnabled(), !props.getCastPhotos().getApiKey().isBlank());
    }

    /** Looks up one name, fetching from TMDB on first request. Null if unresolvable. */
    public CastPhoto photoFor(String name) {
        String slug = slugify(name);
        if (slug.isBlank()) {
            return null;
        }
        return photos.findById(slug).orElseGet(() -> fetch(slug, name.trim()));
    }

    /** Looks up a whole cast list, in the order given, skipping any that fail. */
    public List<CastPhoto> photosFor(List<String> names) {
        List<CastPhoto> result = new ArrayList<>();
        for (String name : names) {
            CastPhoto photo = photoFor(name);
            if (photo != null) {
                result.add(photo);
            }
        }
        return result;
    }

    private CastPhoto fetch(String slug, String displayName) {
        if (!props.getCastPhotos().isEnabled() || props.getCastPhotos().getApiKey().isBlank()) {
            // Not persisted: the moment a key is configured, the very next request
            // resolves normally instead of needing every name re-queried by hand.
            return null;
        }
        try {
            JsonNode person = searchPerson(displayName);
            if (person == null) {
                return photos.save(notFound(slug, displayName, null));
            }
            int tmdbId = person.path("id").asInt(0);
            String profilePath = person.path("profile_path").asString(null);
            if (profilePath == null || profilePath.isBlank()) {
                return photos.save(notFound(slug, displayName, tmdbId));
            }
            Path file = downloadPhoto(slug, profilePath);
            return photos.save(CastPhoto.builder()
                    .id(slug).displayName(displayName).state(CastPhoto.State.READY)
                    .tmdbPersonId(tmdbId).photoPath(file.toString()).fetchedAt(Instant.now())
                    .build());
        } catch (Exception ex) {
            // Deliberately not persisted: a network blip should not permanently mark
            // someone as photo-less. The next request tries again.
            log.warn("Cast photo lookup failed for '{}': {}", displayName, ex.getMessage());
            return null;
        }
    }

    private static CastPhoto notFound(String slug, String displayName, Integer tmdbId) {
        return CastPhoto.builder()
                .id(slug).displayName(displayName).state(CastPhoto.State.NOT_FOUND)
                .tmdbPersonId(tmdbId).fetchedAt(Instant.now()).build();
    }

    private JsonNode searchPerson(String name) throws IOException, InterruptedException {
        String url = "https://api.themoviedb.org/3/search/person?query="
                + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "&api_key=" + props.getCastPhotos().getApiKey();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, props.getCastPhotos().getTimeoutSeconds())))
                .GET().build();
        // Only the API call is throttled, not the image download below: that hits
        // TMDB's separate, high-capacity image CDN, not the rate-limited API.
        rateLimiter.throttle();
        HttpResponse<String> response = sendWithHardTimeout(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("TMDB search returned " + response.statusCode());
        }
        JsonNode results = mapper.readTree(response.body()).path("results");
        return results.isArray() && !results.isEmpty() ? results.get(0) : null;
    }

    private Path downloadPhoto(String slug, String profilePath) throws IOException, InterruptedException {
        String url = "https://image.tmdb.org/t/p/" + props.getCastPhotos().getImageSize() + profilePath;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, props.getCastPhotos().getTimeoutSeconds())))
                .GET().build();
        HttpResponse<byte[]> response = sendWithHardTimeout(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length == 0) {
            throw new IOException("TMDB image download returned " + response.statusCode());
        }
        Files.createDirectories(cacheRoot);
        Path file = cacheRoot.resolve(slug + ".jpg");
        Files.write(file, response.body());
        return file;
    }

    /**
     * {@link HttpRequest.Builder#timeout} is supposed to bound a call on its own, but a
     * 2026-09-19/20 incident showed it doesn't always, on this exact API: a {@code
     * TmdbMovieService} call sat blocked for 12+ minutes with no error, freezing the
     * whole scan thread behind it. Sending async and bounding the *wait* with {@code
     * get(timeout, unit)} enforces the deadline from outside the request, so this
     * thread can never be held hostage by whatever the request-level timeout misses.
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

    /** Resolves the cached photo file for serving, confirmed inside the cache directory. */
    public Path fileFor(String slug) {
        CastPhoto photo = photos.findById(slug).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "No cast photo for this name"));
        if (photo.getState() != CastPhoto.State.READY || photo.getPhotoPath() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No photo available for this person");
        }
        Path file = Path.of(photo.getPhotoPath()).toAbsolutePath().normalize();
        if (!file.startsWith(cacheRoot) || !Files.isRegularFile(file)) {
            throw new ApiException(HttpStatus.GONE, "The cached photo is no longer available");
        }
        return file;
    }

    /** Normalises a name into a stable, filesystem/URL-safe key. */
    static String slugify(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }
}
