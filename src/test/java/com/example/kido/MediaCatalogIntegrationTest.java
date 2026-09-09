package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.Movie;
import com.example.kido.media.catalog.MovieRepository;

/**
 * Drives the media API over real HTTP against in-memory H2.
 *
 * <p>Rows are inserted directly rather than by scanning a disk: the scanner and ffprobe
 * are covered separately, and what matters here is that the API contract, auth gating
 * and the playback-decision logic behave. No {@code app.media.roots} is configured in
 * the test profile, so the suite needs neither a movie disk nor ffmpeg installed —
 * which is also why the byte-serving endpoints are expected to refuse.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaCatalogIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    MovieRepository movies;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> send(String method, String path, String json, String bearer)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        builder.method(method, json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @BeforeEach
    void registerUser() throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = send("POST", "/api/auth/register", """
                {"username":"media_%s","email":"media_%s@example.com",
                 "password":"pw123456","displayName":"Media Tester"}
                """.formatted(unique, unique), null);
        assertEquals(201, response.statusCode(), response.body());
        token = extract(response.body(), "token");
        assertNotNull(token);
    }

    private Movie insertMovie(String title, int year, String videoCodec, String container,
                              int height, String audioCodec) {
        return movies.save(Movie.builder()
                .filePath("D:/Movies/" + title.replace(' ', '.') + "." + year + ".mkv")
                .fileName(title.replace(' ', '.') + "." + year + ".mkv")
                .folderPath("D:/Movies")
                .fileSize(1_400_000_000L)
                .title(title)
                .sortTitle(title.toLowerCase())
                .year(year)
                .runtimeMinutes(148)
                .rating(8.4)
                .mediaInfo(MediaInfo.builder()
                        .container(container)
                        .videoCodec(videoCodec)
                        .audioCodecs(audioCodec)
                        .width(height * 16 / 9)
                        .height(height)
                        .bitrate(8_000_000L)
                        .durationSeconds(8880.0)
                        .probedAt(java.time.Instant.now())
                        .build())
                .build());
    }

    // --- auth gating ---

    @Test
    void catalogRequiresAuthentication() throws Exception {
        assertEquals(403, send("GET", "/api/media/movies", null, null).statusCode());
        assertEquals(403, send("GET", "/api/media/genres", null, null).statusCode());
        assertEquals(403, send("GET", "/api/media/continue-watching", null, null).statusCode());
    }

    @Test
    void scanRequiresAdminKeyOnTopOfAuth() throws Exception {
        // Authenticated but without the admin header.
        assertEquals(403, send("POST", "/api/media/library/scan", null, token).statusCode());
    }

    // --- browsing ---

    @Test
    void browsesAndSearchesLibrary() throws Exception {
        insertMovie("Inception", 2010, "h264", "matroska,webm", 1080, "eac3");
        insertMovie("Arrival", 2016, "hevc", "matroska,webm", 2160, "aac");

        HttpResponse<String> all = send("GET", "/api/media/movies", null, token);
        assertEquals(200, all.statusCode(), all.body());
        assertTrue(all.body().contains("Inception"), all.body());
        assertTrue(all.body().contains("Arrival"), all.body());

        // Title search is a case-insensitive substring match.
        HttpResponse<String> search = send("GET", "/api/media/movies?q=incep", null, token);
        assertEquals(200, search.statusCode());
        assertTrue(search.body().contains("Inception"));
        assertTrue(!search.body().contains("Arrival"), search.body());
    }

    @Test
    void rejectsUnknownSortKey() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/movies?sort=nonsense", null, token);
        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    void missingFilesAreHiddenFromCatalog() throws Exception {
        Movie movie = insertMovie("Gone Movie", 2001, "h264", "mov,mp4", 1080, "aac");
        movie.setMissing(true);
        movies.save(movie);

        HttpResponse<String> response = send("GET", "/api/media/movies", null, token);
        assertTrue(!response.body().contains("Gone Movie"), response.body());

        // The row still exists, so a direct fetch reports 410 rather than 404.
        HttpResponse<String> detail =
                send("GET", "/api/media/movies/" + movie.getId(), null, token);
        assertEquals(410, detail.statusCode(), detail.body());
    }

    @Test
    void returnsNotFoundForUnknownMovie() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/movies/does-not-exist", null, token);
        assertEquals(404, response.statusCode(), response.body());
    }

    // --- playback decision ---

    /**
     * The decision itself is exercised here; that it then really serves bytes is covered
     * end-to-end by {@code MediaStreamingIntegrationTest}, which configures a real root.
     */
    @Test
    void acceptsDirectPlayForCompatibleFile() throws Exception {
        Movie movie = insertMovie("Compatible", 2020, "h264", "mov,mp4,m4a", 1080, "aac");

        HttpResponse<String> response = send("POST",
                "/api/media/movies/" + movie.getId() + "/playback-decision/explain", """
                        {"videoCodecs":["h264"],"audioCodecs":["aac"],
                         "containers":["mp4"],"maxHeight":1080,"supportsHls":true}
                        """, token);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("Direct play"), response.body());
    }

    /** Requesting a real decision needs the file, so an offline disk is a hard failure. */
    @Test
    void playbackDecisionFailsWhenFileIsUnreachable() throws Exception {
        Movie movie = insertMovie("Offline Disk", 2020, "h264", "mov,mp4", 1080, "aac");

        int status = send("POST",
                "/api/media/movies/" + movie.getId() + "/playback-decision", """
                        {"videoCodecs":["h264"],"audioCodecs":["aac"],"containers":["mp4"]}
                        """, token).statusCode();

        assertTrue(status == 403 || status == 404, "got " + status);
    }

    @Test
    void refusesDirectPlayWhenContainerIsNotPlayable() throws Exception {
        // MKV with EAC3: the classic case that forces a transcode on iOS.
        Movie movie = insertMovie("Needs Transcode", 2019, "h264", "matroska,webm", 1080, "eac3");

        HttpResponse<String> explain = send("POST",
                "/api/media/movies/" + movie.getId() + "/playback-decision/explain", """
                        {"videoCodecs":["h264"],"audioCodecs":["aac"],
                         "containers":["mp4"],"maxHeight":1080,"supportsHls":true}
                        """, token);

        assertEquals(200, explain.statusCode(), explain.body());
        assertTrue(explain.body().contains("Container"), explain.body());
        assertTrue(explain.body().contains("Audio codec"), explain.body());
    }

    @Test
    void undeclaredCapabilitiesFallBackToBaselineNotOptimism() throws Exception {
        // A client that declares nothing gets h264/aac only, so HEVC must not direct play.
        Movie movie = insertMovie("Hevc Film", 2021, "hevc", "mov,mp4", 2160, "aac");

        HttpResponse<String> explain = send("POST",
                "/api/media/movies/" + movie.getId() + "/playback-decision/explain",
                "{}", token);

        assertEquals(200, explain.statusCode(), explain.body());
        assertTrue(explain.body().contains("hevc"), explain.body());
    }

    @Test
    void unprobedFileIsNeverDirectPlayed() throws Exception {
        Movie movie = movies.save(Movie.builder()
                .filePath("D:/Movies/Unprobed.2020.mkv")
                .fileName("Unprobed.2020.mkv")
                .title("Unprobed")
                .sortTitle("unprobed")
                .fileSize(1000L)
                .build());

        HttpResponse<String> explain = send("POST",
                "/api/media/movies/" + movie.getId() + "/playback-decision/explain", """
                        {"videoCodecs":["h264","hevc"],"audioCodecs":["aac"],"containers":["mkv"]}
                        """, token);

        // Even with generous client capabilities, an unprobed file must not direct play:
        // there is nothing to compare the declared codecs against.
        assertEquals(200, explain.statusCode(), explain.body());
        assertTrue(explain.body().contains("not been probed"), explain.body());
        assertTrue(!explain.body().contains("Direct play"), explain.body());
    }

    // --- progress ---

    @Test
    void recordsResumePositionAndSurfacesItInContinueWatching() throws Exception {
        Movie movie = insertMovie("Resume Me", 2015, "h264", "mov,mp4", 1080, "aac");

        HttpResponse<String> put = send("PUT",
                "/api/media/movies/" + movie.getId() + "/progress",
                "{\"positionSeconds\":1200,\"durationSeconds\":8880}", token);
        assertEquals(200, put.statusCode(), put.body());
        assertTrue(put.body().contains("\"watched\":false"), put.body());

        HttpResponse<String> row = send("GET", "/api/media/continue-watching", null, token);
        assertEquals(200, row.statusCode(), row.body());
        assertTrue(row.body().contains("Resume Me"), row.body());
        assertTrue(row.body().contains("\"percentComplete\":14"), row.body());

        // A second report replaces the first rather than appending a row.
        send("PUT", "/api/media/movies/" + movie.getId() + "/progress",
                "{\"positionSeconds\":2400,\"durationSeconds\":8880}", token);
        HttpResponse<String> progress =
                send("GET", "/api/media/movies/" + movie.getId() + "/progress", null, token);
        assertTrue(progress.body().contains("2400"), progress.body());
    }

    @Test
    void nearTheEndCountsAsWatchedAndLeavesContinueWatching() throws Exception {
        Movie movie = insertMovie("Almost Done", 2018, "h264", "mov,mp4", 1080, "aac");

        HttpResponse<String> put = send("PUT",
                "/api/media/movies/" + movie.getId() + "/progress",
                "{\"positionSeconds\":8700,\"durationSeconds\":8880}", token);
        assertEquals(200, put.statusCode(), put.body());
        assertTrue(put.body().contains("\"watched\":true"), put.body());

        HttpResponse<String> row = send("GET", "/api/media/continue-watching", null, token);
        assertTrue(!row.body().contains("Almost Done"), row.body());
    }

    @Test
    void progressIsPerUser() throws Exception {
        Movie movie = insertMovie("Shared Film", 2012, "h264", "mov,mp4", 1080, "aac");
        send("PUT", "/api/media/movies/" + movie.getId() + "/progress",
                "{\"positionSeconds\":600,\"durationSeconds\":8880}", token);

        // A second account must not see the first account's resume point.
        String other = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"other_%s","email":"other_%s@example.com",
                 "password":"pw123456","displayName":"Other Tester"}
                """.formatted(other, other), null);
        assertEquals(201, registered.statusCode(), registered.body());
        String otherToken = extract(registered.body(), "token");

        HttpResponse<String> theirRow =
                send("GET", "/api/media/continue-watching", null, otherToken);
        assertEquals(200, theirRow.statusCode());
        assertTrue(!theirRow.body().contains("Shared Film"), theirRow.body());
    }

    @Test
    void resetClearsResumePoint() throws Exception {
        Movie movie = insertMovie("Forget Me", 2014, "h264", "mov,mp4", 1080, "aac");
        send("PUT", "/api/media/movies/" + movie.getId() + "/progress",
                "{\"positionSeconds\":900,\"durationSeconds\":8880}", token);

        assertEquals(204, send("DELETE", "/api/media/movies/" + movie.getId() + "/progress",
                null, token).statusCode());
        assertEquals(404, send("GET", "/api/media/movies/" + movie.getId() + "/progress",
                null, token).statusCode());
    }

    // --- library status ---

    @Test
    void reportsScanStatusWithNoRootsConfigured() throws Exception {
        HttpResponse<String> response = send("GET", "/api/media/library/status", null, token);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"running\":false"), response.body());
        assertTrue(response.body().contains("\"rootsConfigured\":0"), response.body());
    }

    /** Streaming needs a real file inside a configured root; neither exists in tests. */
    @Test
    void streamRefusesWhenPathIsOutsideConfiguredRoots() throws Exception {
        Movie movie = insertMovie("No Disk", 2013, "h264", "mov,mp4", 1080, "aac");
        int status = send("GET", "/api/media/movies/" + movie.getId() + "/stream", null, token)
                .statusCode();
        assertTrue(status == 403 || status == 404, "got " + status);
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
