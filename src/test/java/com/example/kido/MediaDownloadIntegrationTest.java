package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.downloads.DownloadJob;
import com.example.kido.media.downloads.DownloadJobRepository;

/**
 * Covers offline downloads, the away-from-home cost calculation and profile settings.
 *
 * <p>The passthrough path is exercised end to end, including fetching the bytes, since
 * it needs no ffmpeg and is the case that should happen most often — an H.264/AAC MP4
 * the phone can already play is handed over untouched rather than re-encoded.
 *
 * <p>Conversion is verified up to the point of queueing rather than to completion:
 * running ffmpeg over a fixture would make the suite depend on a binary being installed
 * and take minutes. The state machine around it — queueing, cancelling, ownership,
 * refusing to serve an unfinished copy — is all tested here; the ffmpeg invocation and
 * its progress parsing are the parts that need a real file to prove.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaDownloadIntegrationTest {

    private static final int FILE_SIZE = 3000;

    private static Path mediaRoot;
    private static Path playableFile;
    private static Path awkwardFile;

    @DynamicPropertySource
    static void configureLibrary(DynamicPropertyRegistry registry) {
        try {
            mediaRoot = Files.createTempDirectory("kido-download-root");

            playableFile = mediaRoot.resolve("Playable.2021.720p.mp4");
            byte[] payload = new byte[FILE_SIZE];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i % 256);
            }
            Files.write(playableFile, payload);

            awkwardFile = mediaRoot.resolve("Awkward.2019.2160p.mkv");
            Files.write(awkwardFile, payload);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not build download fixture", ex);
        }
        registry.add("app.media.roots", () -> mediaRoot.toString());
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (mediaRoot == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(mediaRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort on a temp directory.
                }
            });
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    @Autowired
    DownloadJobRepository jobs;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private String profileId;
    private String playableId;
    private String awkwardId;

    /** Capabilities of a typical modern phone: H.264/AAC in MP4, up to 1080p. */
    private static final String PHONE_CAPS = """
            {"deviceName":"Test Phone","videoCodecs":["h264"],"audioCodecs":["aac"],
             "containers":["mp4"],"maxHeight":1080,"supportsHls":true}
            """;

    private HttpResponse<String> send(String method, String path, String json)
            throws Exception {
        return http.send(request(method, path, json).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> sendBytes(String method, String path) throws Exception {
        return http.send(request(method, path, null).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest.Builder request(String method, String path, String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (profileId != null) {
            builder.header("X-Profile-Id", profileId);
        }
        builder.method(method, json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return builder;
    }

    @BeforeEach
    void setUp() throws Exception {
        token = null;
        profileId = null;
        jobs.deleteAll();

        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"dl_%s","email":"dl_%s@example.com","password":"pw123456",
                 "displayName":"Download Tester"}
                """.formatted(unique, unique));
        assertEquals(201, registered.statusCode(), registered.body());
        token = extract(registered.body(), "token");

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"Saver\",\"ageMode\":\"OLDER\"}");
        assertEquals(201, profile.statusCode(), profile.body());
        profileId = extract(profile.body(), "id");

        playableId = ensureItem(playableFile, "Playable", "mov,mp4,m4a", "h264", "aac", 720);
        awkwardId = ensureItem(awkwardFile, "Awkward", "matroska,webm", "hevc", "eac3", 2160);
    }

    private String ensureItem(Path file, String title, String container,
                              String videoCodec, String audioCodec, int height) {
        String path = file.toAbsolutePath().normalize().toString();
        return items.findByFilePath(path).orElseGet(() -> items.save(MediaItem.builder()
                .type(MediaType.FILM)
                .filePath(path)
                .fileName(file.getFileName().toString())
                .folderPath(file.getParent().toString())
                .fileSize(FILE_SIZE)
                .title(title)
                .sortTitle(title.toLowerCase())
                .mediaInfo(MediaInfo.builder()
                        .container(container)
                        .videoCodec(videoCodec)
                        .audioCodecs(audioCodec)
                        .width(height * 16 / 9)
                        .height(height)
                        .bitrate(6_000_000L)
                        .durationSeconds(1800.0)
                        .probedAt(Instant.now())
                        .build())
                .build())).getId();
    }

    private HttpResponse<String> requestDownload(String itemId, Integer height) throws Exception {
        String body = height == null
                ? "{\"capabilities\":%s}".formatted(PHONE_CAPS)
                : "{\"height\":%d,\"capabilities\":%s}".formatted(height, PHONE_CAPS);
        return send("POST", "/api/media/items/" + itemId + "/download", body);
    }

    // --- passthrough: the case that should happen most ---

    /**
     * The file already plays on this device at the requested size, so it must be
     * offered as-is: no CPU, no wait, no second generation of compression.
     */
    @Test
    void alreadyPlayableFileIsOfferedWithoutConversion() throws Exception {
        HttpResponse<String> response = requestDownload(playableId, 1080);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"mode\":\"PASSTHROUGH\""), response.body());
        assertTrue(response.body().contains("\"state\":\"READY\""), response.body());
        assertTrue(response.body().contains("\"percent\":100"), response.body());
        assertTrue(response.body().contains("SAVED · ORIGINAL QUALITY"), response.body());
    }

    @Test
    void passthroughCopyCanBeFetchedByteForByte() throws Exception {
        String jobId = extract(requestDownload(playableId, 1080).body(), "jobId");
        assertNotNull(jobId);

        HttpResponse<byte[]> file =
                sendBytes("GET", "/api/media/downloads/" + jobId + "/file");
        assertEquals(200, file.statusCode());
        assertEquals(FILE_SIZE, file.body().length);
        // Named from the title, not the release filename.
        assertTrue(file.headers().firstValue("Content-Disposition").orElse("")
                .contains("Playable"), file.headers().map().toString());
    }

    /** Resumable, because a phone on mobile data will drop mid-download. */
    @Test
    void downloadFetchSupportsRangeResume() throws Exception {
        String jobId = extract(requestDownload(playableId, 1080).body(), "jobId");

        HttpResponse<byte[]> partial = http.send(
                request("GET", "/api/media/downloads/" + jobId + "/file", null)
                        .header("Range", "bytes=1000-1999").build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(206, partial.statusCode());
        assertEquals(1000, partial.body().length);
        assertEquals("bytes 1000-1999/" + FILE_SIZE,
                partial.headers().firstValue("Content-Range").orElse(null));
    }

    /** A 4K source must not pass through when 720p was asked for, however capable the phone. */
    @Test
    void oversizedSourceIsConvertedNotPassedThrough() throws Exception {
        HttpResponse<String> response = requestDownload(playableId, 480);

        assertEquals(202, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"mode\":\"CONVERT\""), response.body());
        assertTrue(response.body().contains("\"targetHeight\":480"), response.body());
    }

    /** An undecodable container means conversion regardless of size. */
    @Test
    void incompatibleFileIsQueuedForConversion() throws Exception {
        HttpResponse<String> response = requestDownload(awkwardId, 1080);

        assertEquals(202, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"mode\":\"CONVERT\""), response.body());
        assertTrue(response.body().contains("\"sourceHeight\":2160"), response.body());
        assertTrue(response.body().contains("\"targetHeight\":1080"), response.body());
        // Freshly queued, so the honest label is that it has not started.
        assertTrue(response.body().contains("WAITING TO CONVERT"), response.body());
    }

    /**
     * The card's mono line, built server-side so wording and percentage cannot
     * disagree. Driven by setting the state directly rather than by running ffmpeg,
     * which is what the label formatting actually depends on.
     */
    @Test
    void convertingLabelNamesBothResolutionsAndThePercentage() throws Exception {
        String jobId = extract(requestDownload(awkwardId, 1080).body(), "jobId");

        DownloadJob job = jobs.findById(jobId).orElseThrow();
        job.setState(DownloadJob.State.CONVERTING);
        job.setPercent(68);
        jobs.save(job);

        HttpResponse<String> status = send("GET", "/api/media/downloads/" + jobId, null);
        assertEquals(200, status.statusCode(), status.body());
        // 2160 reads as "4K" to a person; the rest as their own number.
        assertTrue(status.body().contains("CONVERTING 4K -> 1080P · 68%"), status.body());
    }

    /** The pre-conversion size estimate is what warns about a data plan. */
    @Test
    void queuedConversionReportsASizeEstimate() throws Exception {
        HttpResponse<String> response = requestDownload(awkwardId, 720);
        String body = response.body();
        assertTrue(body.contains("\"bytes\":"), body);

        DownloadJob job = jobs.findAll().stream()
                .filter(candidate -> candidate.getMediaItemId().equals(awkwardId))
                .findFirst().orElseThrow();
        assertNotNull(job.getEstimatedBytes());
        // 30 minutes at roughly 2.6 Mbit/s is in the hundreds of megabytes.
        assertTrue(job.getEstimatedBytes() > 100_000_000L,
                "estimate looks wrong: " + job.getEstimatedBytes());
    }

    // --- lifecycle ---

    /** Tapping download twice is a double-tap, not a request for two copies. */
    @Test
    void repeatRequestReturnsTheSameJob() throws Exception {
        String first = extract(requestDownload(awkwardId, 720).body(), "jobId");
        String second = extract(requestDownload(awkwardId, 720).body(), "jobId");
        assertEquals(first, second);
        assertEquals(1, jobs.findAll().size());
    }

    @Test
    void unfinishedCopyCannotBeFetched() throws Exception {
        String jobId = extract(requestDownload(awkwardId, 720).body(), "jobId");
        HttpResponse<String> file = send("GET", "/api/media/downloads/" + jobId + "/file", null);
        // 409 while converting, or 500 if ffmpeg is absent and it already failed —
        // either way it must not hand over a partial file.
        assertTrue(file.statusCode() >= 400, file.body());
    }

    @Test
    void cancellingStopsTheJob() throws Exception {
        String jobId = extract(requestDownload(awkwardId, 720).body(), "jobId");

        HttpResponse<String> cancelled =
                send("DELETE", "/api/media/downloads/" + jobId, null);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertTrue(cancelled.body().contains("\"state\":\"CANCELLED\""), cancelled.body());
    }

    @Test
    void savedListSummarisesCountsAndBytes() throws Exception {
        requestDownload(playableId, 1080);

        HttpResponse<String> list = send("GET", "/api/media/downloads", null);
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("\"readyCount\":1"), list.body());
        assertTrue(list.body().contains("\"readyBytes\":" + FILE_SIZE), list.body());
        assertTrue(list.body().contains("Playable"), list.body());
    }

    /** A download belongs to the profile that asked for it. */
    @Test
    void anotherProfileCannotSeeOrCancelTheDownload() throws Exception {
        String jobId = extract(requestDownload(playableId, 1080).body(), "jobId");

        HttpResponse<String> second = send("POST", "/api/profiles",
                "{\"name\":\"Sibling\",\"ageMode\":\"YOUNG\"}");
        assertEquals(201, second.statusCode(), second.body());
        String siblingId = extract(second.body(), "id");

        String originalProfile = profileId;
        profileId = siblingId;
        try {
            assertEquals(404, send("GET", "/api/media/downloads/" + jobId, null).statusCode());
            assertEquals(404, send("DELETE", "/api/media/downloads/" + jobId, null).statusCode());

            HttpResponse<String> list = send("GET", "/api/media/downloads", null);
            assertTrue(list.body().contains("\"readyCount\":0"), list.body());
        } finally {
            profileId = originalProfile;
        }
    }

    /**
     * The Saved list only grows -- cancelled and expired jobs stay as history -- so it
     * has to be paged, and the header totals have to describe the whole list rather
     * than the visible page.
     */
    @Test
    void savedListIsPagedWithWholeListTotals() throws Exception {
        // Two ready copies, so a page size of one splits them.
        requestDownload(playableId, 1080);
        MediaItem second = items.save(MediaItem.builder()
                .type(MediaType.FILM)
                .filePath(mediaRoot.resolve("Second.2020.720p.mp4").toString())
                .fileName("Second.2020.720p.mp4")
                .fileSize(FILE_SIZE)
                .title("Second Film")
                .sortTitle("second film")
                .mediaInfo(MediaInfo.builder()
                        .container("mov,mp4,m4a").videoCodec("h264").audioCodecs("aac")
                        .height(720).durationSeconds(600.0).probedAt(Instant.now()).build())
                .build());
        Files.write(mediaRoot.resolve("Second.2020.720p.mp4"), new byte[FILE_SIZE]);
        requestDownload(second.getId(), 1080);

        HttpResponse<String> firstPage = send("GET", "/api/media/downloads?page=0&size=1", null);
        assertEquals(200, firstPage.statusCode(), firstPage.body());
        assertTrue(firstPage.body().contains("\"totalItems\":2"), firstPage.body());
        assertTrue(firstPage.body().contains("\"totalPages\":2"), firstPage.body());
        // Both ready copies are counted even though only one is on this page.
        assertTrue(firstPage.body().contains("\"readyCount\":2"), firstPage.body());
        assertTrue(firstPage.body().contains("\"readyBytes\":" + (FILE_SIZE * 2)),
                firstPage.body());

        HttpResponse<String> secondPage = send("GET", "/api/media/downloads?page=1&size=1", null);
        assertTrue(secondPage.body().contains("\"page\":1"), secondPage.body());
    }

    @Test
    void savedListPageSizeIsCapped() throws Exception {
        HttpResponse<String> response = send("GET", "/api/media/downloads?size=9999", null);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"size\":100"), response.body());
    }

    @Test
    void unknownJobIsNotFound() throws Exception {
        assertEquals(404, send("GET", "/api/media/downloads/nope", null).statusCode());
    }

    @Test
    void downloadsRequireAuthentication() throws Exception {
        String saved = token;
        token = null;
        try {
            assertEquals(403, send("GET", "/api/media/downloads", null).statusCode());
        } finally {
            token = saved;
        }
    }

    // --- away from home ---

    /** Tests connect over loopback, which is as "at home" as it gets. */
    @Test
    void loopbackClientIsReportedAsAtHome() throws Exception {
        HttpResponse<String> response = send("GET", "/api/media/reachability", null);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"atHome\":true"), response.body());
        assertTrue(response.body().contains("\"location\":\"HOME\""), response.body());
        // No data cost to warn about on the home network.
        assertTrue(response.body().contains("\"uploadBitsPerSecond\":null"), response.body());
    }

    @Test
    void playbackCostReportsBothOptions() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/items/" + awkwardId + "/playback-cost?height=720", null);
        assertEquals(200, response.statusCode(), response.body());

        assertTrue(response.body().contains("\"originalBytes\":" + FILE_SIZE), response.body());
        // The transcode estimate is what the sheet quotes as "~1.4 GB".
        assertTrue(response.body().contains("\"transcodedBytes\":"), response.body());
        assertTrue(response.body().contains("\"transcodeHeight\":720"), response.body());
        assertTrue(response.body().contains("no data cost"), response.body());
    }

    // --- settings ---

    @Test
    void settingsDefaultToAskingEveryTime() throws Exception {
        HttpResponse<String> response = send("GET", "/api/media/settings", null);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"awayBehaviour\":\"ASK\""), response.body());
        assertTrue(response.body().contains("\"showTechnicalBadges\":true"), response.body());
    }

    /** "When I leave home Wi-Fi, play saved copies only" plus "Remember this choice". */
    @Test
    void awayBehaviourCanBeRemembered() throws Exception {
        HttpResponse<String> updated = send("PUT", "/api/media/settings",
                "{\"awayBehaviour\":\"SAVED_ONLY\",\"awayMaxHeight\":480}");
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(updated.body().contains("\"awayBehaviour\":\"SAVED_ONLY\""), updated.body());

        HttpResponse<String> reread = send("GET", "/api/media/settings", null);
        assertTrue(reread.body().contains("\"awayBehaviour\":\"SAVED_ONLY\""), reread.body());
        assertTrue(reread.body().contains("\"awayMaxHeight\":480"), reread.body());
    }

    /** The technical-badges toggle follows the person, not the install. */
    @Test
    void technicalBadgesToggleIsPerProfile() throws Exception {
        send("PUT", "/api/media/settings", "{\"showTechnicalBadges\":false}");

        HttpResponse<String> second = send("POST", "/api/profiles",
                "{\"name\":\"Other\",\"ageMode\":\"OLDER\"}");
        String otherId = extract(second.body(), "id");

        String original = profileId;
        profileId = otherId;
        try {
            HttpResponse<String> theirs = send("GET", "/api/media/settings", null);
            assertTrue(theirs.body().contains("\"showTechnicalBadges\":true"), theirs.body());
        } finally {
            profileId = original;
        }

        HttpResponse<String> mine = send("GET", "/api/media/settings", null);
        assertTrue(mine.body().contains("\"showTechnicalBadges\":false"), mine.body());
    }

    @Test
    void rejectsUnknownAwayBehaviour() throws Exception {
        assertEquals(400, send("PUT", "/api/media/settings",
                "{\"awayBehaviour\":\"nonsense\"}").statusCode());
    }

    @Test
    void nonVideoCannotBeDownloaded() throws Exception {
        MediaItem song = items.save(MediaItem.builder()
                .type(MediaType.MUSIC)
                .filePath(mediaRoot.resolve("song.mp3").toString())
                .fileName("song.mp3")
                .fileSize(4_000_000L)
                .title("A Song")
                .sortTitle("a song")
                .build());

        assertEquals(400, requestDownload(song.getId(), 720).statusCode());
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
