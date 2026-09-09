package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;

/**
 * Drives the media API over real HTTP against in-memory H2.
 *
 * <p>Rows are inserted directly rather than by scanning a disk: the scanner and ffprobe
 * are covered separately, and what matters here is that the API contract, auth gating,
 * profile scoping and the playback-decision logic behave. No media library is configured
 * in the test profile, so the suite needs neither a media disk nor ffmpeg installed —
 * which is also why the byte-serving endpoints are expected to refuse here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaCatalogIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private String profileId;

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> send(String method, String path, String json, String bearer)
            throws Exception {
        return send(method, path, json, bearer, profileId);
    }

    private HttpResponse<String> send(String method, String path, String json,
                                      String bearer, String profile) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (profile != null) {
            builder.header("X-Profile-Id", profile);
        }
        builder.method(method, json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Registers an account and gives it one profile, since watch state hangs off profiles. */
    private String[] registerWithProfile(String prefix) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"%s_%s","email":"%s_%s@example.com",
                 "password":"pw123456","displayName":"Media Tester"}
                """.formatted(prefix, unique, prefix, unique), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        String bearer = extract(registered.body(), "token");
        assertNotNull(bearer);

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"Tester\",\"ageMode\":\"OLDER\"}", bearer, null);
        assertEquals(201, profile.statusCode(), profile.body());
        return new String[]{bearer, extract(profile.body(), "id")};
    }

    @BeforeEach
    void setUp() throws Exception {
        String[] credentials = registerWithProfile("media");
        token = credentials[0];
        profileId = credentials[1];
    }

    private MediaItem insert(String title, MediaType type, int year, String videoCodec,
                             String container, int height, String audioCodec) {
        return items.save(MediaItem.builder()
                .type(type)
                .libraryName(type.label())
                .filePath("D:/Media/" + title.replace(' ', '.') + "." + year + ".mkv")
                .fileName(title.replace(' ', '.') + "." + year + ".mkv")
                .folderPath("D:/Media")
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
                        .probedAt(Instant.now())
                        .build())
                .build());
    }

    private MediaItem insertFilm(String title, int year, String videoCodec,
                                 String container, int height, String audioCodec) {
        return insert(title, MediaType.FILM, year, videoCodec, container, height, audioCodec);
    }

    // --- auth and profile gating ---

    @Test
    void catalogRequiresAuthentication() throws Exception {
        assertEquals(403, send("GET", "/api/media/items", null, null).statusCode());
        assertEquals(403, send("GET", "/api/media/genres", null, null).statusCode());
        assertEquals(403, send("GET", "/api/media/continue-watching", null, null).statusCode());
    }

    @Test
    void scanRequiresAdminKeyOnTopOfAuth() throws Exception {
        assertEquals(403, send("POST", "/api/media/library/scan", null, token).statusCode());
    }

    /**
     * The header only selects among the caller's own profiles — a profile id belonging
     * to another household must not resolve.
     */
    @Test
    void rejectsProfileBelongingToAnotherAccount() throws Exception {
        String[] other = registerWithProfile("other");
        HttpResponse<String> response =
                send("GET", "/api/media/continue-watching", null, token, other[1]);
        assertEquals(404, response.statusCode(), response.body());
    }

    @Test
    void fallsBackToFirstProfileWithoutHeader() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/continue-watching", null, token, null);
        assertEquals(200, response.statusCode(), response.body());
    }

    // --- browsing ---

    @Test
    void browsesAndSearchesLibrary() throws Exception {
        insertFilm("Inception", 2010, "h264", "matroska,webm", 1080, "eac3");
        insertFilm("Arrival", 2016, "hevc", "matroska,webm", 2160, "aac");

        HttpResponse<String> all = send("GET", "/api/media/items", null, token);
        assertEquals(200, all.statusCode(), all.body());
        assertTrue(all.body().contains("Inception"), all.body());
        assertTrue(all.body().contains("Arrival"), all.body());

        HttpResponse<String> search = send("GET", "/api/media/items?q=incep", null, token);
        assertEquals(200, search.statusCode());
        assertTrue(search.body().contains("Inception"));
        assertTrue(!search.body().contains("Arrival"), search.body());
    }

    /** The category chips must actually partition the library. */
    @Test
    void filtersByCategory() throws Exception {
        insertFilm("A Film", 2010, "h264", "mov,mp4", 1080, "aac");
        insert("An Anime", MediaType.ANIME, 2015, "h264", "matroska,webm", 1080, "aac");
        insert("Our Holiday", MediaType.HOME_VIDEO, 2024, "h264", "mov,mp4", 1080, "aac");

        HttpResponse<String> films = send("GET", "/api/media/items?category=FILM", null, token);
        assertTrue(films.body().contains("A Film"), films.body());
        assertTrue(!films.body().contains("An Anime"), films.body());

        // "ours" is the chip label for home video.
        HttpResponse<String> ours = send("GET", "/api/media/items?category=ours", null, token);
        assertTrue(ours.body().contains("Our Holiday"), ours.body());
        assertTrue(!ours.body().contains("A Film"), ours.body());

        HttpResponse<String> all = send("GET", "/api/media/items?category=all", null, token);
        assertTrue(all.body().contains("A Film") && all.body().contains("An Anime")
                && all.body().contains("Our Holiday"), all.body());
    }

    @Test
    void rejectsUnknownCategoryAndSort() throws Exception {
        assertEquals(400, send("GET", "/api/media/items?category=nonsense", null, token)
                .statusCode());
        assertEquals(400, send("GET", "/api/media/items?sort=nonsense", null, token)
                .statusCode());
    }

    /**
     * Backs the 4K ONLY chip.
     *
     * <p>The two titles share no substring on purpose: asserting absence with
     * {@code contains} silently passes when one name is a suffix of the other.
     */
    @Test
    void filtersByMinimumHeight() throws Exception {
        insertFilm("Nightfall Drive", 2010, "h264", "mov,mp4", 1080, "aac");
        insertFilm("Sandworm", 2020, "hevc", "mov,mp4", 2160, "aac");

        HttpResponse<String> uhd = send("GET", "/api/media/items?minHeight=2160", null, token);
        assertTrue(uhd.body().contains("Sandworm"), uhd.body());
        assertTrue(!uhd.body().contains("Nightfall Drive"), uhd.body());
    }

    @Test
    void reportsLibrarySummaryWithPerCategoryCounts() throws Exception {
        insertFilm("Summary Film", 2010, "h264", "mov,mp4", 1080, "aac");
        insert("Summary Song", MediaType.MUSIC, 2010, null, null, 0, "mp3");

        HttpResponse<String> response = send("GET", "/api/media/library-summary", null, token);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"FILM\""), response.body());
        assertTrue(response.body().contains("\"MUSIC\""), response.body());
        assertTrue(response.body().contains("\"label\":\"Ours\""), response.body());
    }

    @Test
    void missingFilesAreHiddenFromCatalog() throws Exception {
        MediaItem item = insertFilm("Gone Film", 2001, "h264", "mov,mp4", 1080, "aac");
        item.setMissing(true);
        items.save(item);

        HttpResponse<String> response = send("GET", "/api/media/items", null, token);
        assertTrue(!response.body().contains("Gone Film"), response.body());

        // The row still exists, so a direct fetch reports 410 rather than 404.
        assertEquals(410, send("GET", "/api/media/items/" + item.getId(), null, token)
                .statusCode());
    }

    @Test
    void returnsNotFoundForUnknownItem() throws Exception {
        assertEquals(404, send("GET", "/api/media/items/does-not-exist", null, token)
                .statusCode());
    }

    // --- timeline ---

    @Test
    void groupsHomeVideosByMonth() throws Exception {
        MediaItem december = insert("Christmas", MediaType.HOME_VIDEO, 2024,
                "h264", "mov,mp4", 1080, "aac");
        december.setCapturedAt(Instant.parse("2024-12-25T10:00:00Z"));
        items.save(december);

        HttpResponse<String> response =
                send("GET", "/api/media/timeline?groupBy=date", null, token);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("DEC / 2024"), response.body());
        assertTrue(response.body().contains("Christmas"), response.body());
    }

    @Test
    void countsUndatedTimelineItems() throws Exception {
        insert("Undated Clip", MediaType.HOME_VIDEO, 2024, "h264", "mov,mp4", 1080, "aac");

        HttpResponse<String> response = send("GET", "/api/media/timeline", null, token);
        assertEquals(200, response.statusCode(), response.body());
        // Drives the client's "clips have no date — tag them?" nudge.
        assertTrue(response.body().contains("\"undatedCount\":"), response.body());
        assertTrue(response.body().contains("NO DATE"), response.body());
    }

    @Test
    void rejectsUnknownTimelineGrouping() throws Exception {
        assertEquals(400, send("GET", "/api/media/timeline?groupBy=nonsense", null, token)
                .statusCode());
    }

    // --- playback decision ---

    @Test
    void acceptsDirectPlayForCompatibleFile() throws Exception {
        MediaItem item = insertFilm("Compatible", 2020, "h264", "mov,mp4,m4a", 1080, "aac");

        HttpResponse<String> response = send("POST",
                "/api/media/items/" + item.getId() + "/playback-decision/explain", """
                        {"videoCodecs":["h264"],"audioCodecs":["aac"],
                         "containers":["mp4"],"maxHeight":1080,"supportsHls":true}
                        """, token);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("Direct play"), response.body());
    }

    @Test
    void refusesDirectPlayWhenContainerIsNotPlayable() throws Exception {
        // MKV with EAC3: the classic case that forces a transcode on iOS.
        MediaItem item = insertFilm("Needs Transcode", 2019, "h264", "matroska,webm", 1080, "eac3");

        HttpResponse<String> explain = send("POST",
                "/api/media/items/" + item.getId() + "/playback-decision/explain", """
                        {"videoCodecs":["h264"],"audioCodecs":["aac"],
                         "containers":["mp4"],"maxHeight":1080,"supportsHls":true}
                        """, token);

        assertEquals(200, explain.statusCode(), explain.body());
        assertTrue(explain.body().contains("Container"), explain.body());
        assertTrue(explain.body().contains("Audio codec"), explain.body());
    }

    @Test
    void undeclaredCapabilitiesFallBackToBaselineNotOptimism() throws Exception {
        MediaItem item = insertFilm("Hevc Film", 2021, "hevc", "mov,mp4", 2160, "aac");

        HttpResponse<String> explain = send("POST",
                "/api/media/items/" + item.getId() + "/playback-decision/explain",
                "{}", token);

        assertEquals(200, explain.statusCode(), explain.body());
        assertTrue(explain.body().contains("hevc"), explain.body());
    }

    @Test
    void unprobedFileIsNeverDirectPlayed() throws Exception {
        MediaItem item = items.save(MediaItem.builder()
                .type(MediaType.FILM)
                .filePath("D:/Media/Unprobed.2020.mkv")
                .fileName("Unprobed.2020.mkv")
                .title("Unprobed")
                .sortTitle("unprobed")
                .fileSize(1000L)
                .build());

        HttpResponse<String> explain = send("POST",
                "/api/media/items/" + item.getId() + "/playback-decision/explain", """
                        {"videoCodecs":["h264","hevc"],"audioCodecs":["aac"],"containers":["mkv"]}
                        """, token);

        assertEquals(200, explain.statusCode(), explain.body());
        assertTrue(explain.body().contains("not been probed"), explain.body());
        assertTrue(!explain.body().contains("Direct play"), explain.body());
    }

    @Test
    void playbackDecisionFailsWhenFileIsUnreachable() throws Exception {
        MediaItem item = insertFilm("Offline Disk", 2020, "h264", "mov,mp4", 1080, "aac");
        int status = send("POST", "/api/media/items/" + item.getId() + "/playback-decision", """
                {"videoCodecs":["h264"],"audioCodecs":["aac"],"containers":["mp4"]}
                """, token).statusCode();
        assertTrue(status == 403 || status == 404, "got " + status);
    }

    // --- progress, per profile ---

    @Test
    void recordsResumePositionAndSurfacesItInContinueWatching() throws Exception {
        MediaItem item = insertFilm("Resume Me", 2015, "h264", "mov,mp4", 1080, "aac");

        HttpResponse<String> put = send("PUT",
                "/api/media/items/" + item.getId() + "/progress",
                "{\"positionSeconds\":1200,\"durationSeconds\":8880}", token);
        assertEquals(200, put.statusCode(), put.body());
        assertTrue(put.body().contains("\"watched\":false"), put.body());

        HttpResponse<String> row = send("GET", "/api/media/continue-watching", null, token);
        assertEquals(200, row.statusCode(), row.body());
        assertTrue(row.body().contains("Resume Me"), row.body());
        assertTrue(row.body().contains("\"percentComplete\":14"), row.body());

        // A second report replaces the first rather than appending a row.
        send("PUT", "/api/media/items/" + item.getId() + "/progress",
                "{\"positionSeconds\":2400,\"durationSeconds\":8880}", token);
        HttpResponse<String> progress =
                send("GET", "/api/media/items/" + item.getId() + "/progress", null, token);
        assertTrue(progress.body().contains("2400"), progress.body());
    }

    @Test
    void nearTheEndCountsAsWatchedAndLeavesContinueWatching() throws Exception {
        MediaItem item = insertFilm("Almost Done", 2018, "h264", "mov,mp4", 1080, "aac");

        HttpResponse<String> put = send("PUT",
                "/api/media/items/" + item.getId() + "/progress",
                "{\"positionSeconds\":8700,\"durationSeconds\":8880}", token);
        assertEquals(200, put.statusCode(), put.body());
        assertTrue(put.body().contains("\"watched\":true"), put.body());

        HttpResponse<String> row = send("GET", "/api/media/continue-watching", null, token);
        assertTrue(!row.body().contains("Almost Done"), row.body());
    }

    /** Two profiles on the same account must not share resume points. */
    @Test
    void progressIsPerProfileNotPerAccount() throws Exception {
        MediaItem item = insertFilm("Shared Film", 2012, "h264", "mov,mp4", 1080, "aac");
        send("PUT", "/api/media/items/" + item.getId() + "/progress",
                "{\"positionSeconds\":600,\"durationSeconds\":8880}", token);

        HttpResponse<String> second = send("POST", "/api/profiles",
                "{\"name\":\"Sibling\",\"ageMode\":\"YOUNG\"}", token, null);
        assertEquals(201, second.statusCode(), second.body());
        String siblingId = extract(second.body(), "id");

        HttpResponse<String> theirRow =
                send("GET", "/api/media/continue-watching", null, token, siblingId);
        assertEquals(200, theirRow.statusCode(), theirRow.body());
        assertTrue(!theirRow.body().contains("Shared Film"), theirRow.body());

        // The original profile still has it.
        HttpResponse<String> mine = send("GET", "/api/media/continue-watching", null, token);
        assertTrue(mine.body().contains("Shared Film"), mine.body());
    }

    @Test
    void resetClearsResumePoint() throws Exception {
        MediaItem item = insertFilm("Forget Me", 2014, "h264", "mov,mp4", 1080, "aac");
        send("PUT", "/api/media/items/" + item.getId() + "/progress",
                "{\"positionSeconds\":900,\"durationSeconds\":8880}", token);

        assertEquals(204, send("DELETE", "/api/media/items/" + item.getId() + "/progress",
                null, token).statusCode());
        assertEquals(404, send("GET", "/api/media/items/" + item.getId() + "/progress",
                null, token).statusCode());
    }

    // --- player state ---

    @Test
    void storesSubtitleOffsetAndTrackChoices() throws Exception {
        MediaItem item = insertFilm("Offset Me", 2016, "h264", "mov,mp4", 1080, "aac");

        HttpResponse<String> offset = send("PUT",
                "/api/media/items/" + item.getId() + "/subtitle-offset",
                "{\"offsetSeconds\":1.4}", token);
        assertEquals(200, offset.statusCode(), offset.body());
        assertTrue(offset.body().contains("1.4"), offset.body());

        HttpResponse<String> tracks = send("PUT",
                "/api/media/items/" + item.getId() + "/tracks",
                "{\"subtitleTrackIndex\":2,\"audioTrackIndex\":1}", token);
        assertEquals(200, tracks.statusCode(), tracks.body());

        HttpResponse<String> state =
                send("GET", "/api/media/items/" + item.getId() + "/player-state", null, token);
        assertEquals(200, state.statusCode(), state.body());
        assertTrue(state.body().contains("\"subtitleOffsetSeconds\":1.4"), state.body());
        assertTrue(state.body().contains("\"subtitleTrackIndex\":2"), state.body());
        assertTrue(state.body().contains("\"audioTrackIndex\":1"), state.body());
    }

    @Test
    void reportsEmptyChaptersForFileWithNone() throws Exception {
        MediaItem item = insertFilm("No Chapters", 2016, "h264", "mov,mp4", 1080, "aac");
        HttpResponse<String> response =
                send("GET", "/api/media/items/" + item.getId() + "/chapters", null, token);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("[]", response.body().trim());
    }

    @Test
    void trickplayIsAbsentUntilGenerated() throws Exception {
        MediaItem item = insertFilm("No Frames", 2016, "h264", "mov,mp4", 1080, "aac");
        assertEquals(404, send("GET", "/api/media/items/" + item.getId() + "/trickplay",
                null, token).statusCode());
    }

    // --- library status ---

    @Test
    void reportsScanStatusWithNoLibrariesConfigured() throws Exception {
        HttpResponse<String> response = send("GET", "/api/media/library/status", null, token);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"running\":false"), response.body());
        assertTrue(response.body().contains("\"rootsConfigured\":0"), response.body());
    }

    /** Streaming needs a real file inside a configured library; neither exists here. */
    @Test
    void streamRefusesWhenPathIsOutsideConfiguredLibraries() throws Exception {
        MediaItem item = insertFilm("No Disk", 2013, "h264", "mov,mp4", 1080, "aac");
        int status = send("GET", "/api/media/items/" + item.getId() + "/stream", null, token)
                .statusCode();
        assertTrue(status == 403 || status == 404, "got " + status);
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
