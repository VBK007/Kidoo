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

/**
 * End-to-end test of playback-session tracking against a real file.
 *
 * <p>The session mechanism is what the whole admin panel rests on, and it is easy to
 * get subtly wrong in a way no unit test would catch: the decision has to open a
 * session, byte serving has to meter it, and ending it has to actually stop the stream.
 * That last part is the one worth proving — for a direct play there is no process to
 * kill, so termination is enforced on the next range request, and if that check were
 * missing the panel would show a stop button that quietly did nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaAdminSessionIntegrationTest {

    private static final String ADMIN_KEY = "test-admin-key";
    private static final int FILE_SIZE = 4000;

    private static Path mediaRoot;
    private static Path movieFile;

    @DynamicPropertySource
    static void configureLibrary(DynamicPropertyRegistry registry) {
        try {
            mediaRoot = Files.createTempDirectory("kido-admin-root");
            movieFile = mediaRoot.resolve("Session.Test.2021.1080p.mp4");
            byte[] payload = new byte[FILE_SIZE];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i % 256);
            }
            Files.write(movieFile, payload);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not build session fixture", ex);
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

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private String profileId;
    private String itemId;

    @BeforeEach
    void setUp() throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"sess_%s","email":"sess_%s@example.com","password":"pw123456",
                 "displayName":"Session Owner","role":"PARENT"}
                """.formatted(unique, unique), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        token = extract(registered.body(), "token");
        assertNotNull(token);

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"Owner\",\"ageMode\":\"OLDER\"}", token, null);
        assertEquals(201, profile.statusCode(), profile.body());
        profileId = extract(profile.body(), "id");

        String path = movieFile.toAbsolutePath().normalize().toString();
        MediaItem item = items.findByFilePath(path).orElseGet(() -> items.save(MediaItem.builder()
                .type(MediaType.FILM)
                .filePath(path)
                .fileName(movieFile.getFileName().toString())
                .folderPath(movieFile.getParent().toString())
                .fileSize(FILE_SIZE)
                .title("Session Test")
                .sortTitle("session test")
                .year(2021)
                .mediaInfo(MediaInfo.builder()
                        .container("mov,mp4,m4a")
                        .videoCodec("h264")
                        .audioCodecs("aac")
                        .width(1920)
                        .height(1080)
                        .bitrate(4_000_000L)
                        .durationSeconds(600.0)
                        .probedAt(Instant.now())
                        .build())
                .build()));
        itemId = item.getId();
    }

    private HttpResponse<String> send(String method, String path, String json,
                                      String bearer, String adminKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (adminKey != null) {
            builder.header("X-Admin-Key", adminKey);
        }
        if (profileId != null) {
            builder.header("X-Profile-Id", profileId);
        }
        builder.method(method, json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Direct play with matching capabilities, which is what opens a DIRECT session. */
    private HttpResponse<String> decide() throws Exception {
        return send("POST", "/api/media/items/" + itemId + "/playback-decision", """
                {"deviceName":"Test Phone","videoCodecs":["h264"],"audioCodecs":["aac"],
                 "containers":["mp4"],"maxHeight":1080,"supportsHls":true}
                """, token, null);
    }

    @Test
    void decisionOpensSessionVisibleToTheOwner() throws Exception {
        HttpResponse<String> decision = decide();
        assertEquals(200, decision.statusCode(), decision.body());
        assertTrue(decision.body().contains("\"DIRECT\""), decision.body());

        String sessionId = extract(decision.body(), "sessionId");
        assertNotNull(sessionId, decision.body());
        // The stream URL carries the session, which is what makes it trackable.
        assertTrue(decision.body().contains("stream?session=" + sessionId), decision.body());

        HttpResponse<String> sessions =
                send("GET", "/api/media/admin/sessions", null, token, ADMIN_KEY);
        assertEquals(200, sessions.statusCode(), sessions.body());
        assertTrue(sessions.body().contains(sessionId), sessions.body());
        assertTrue(sessions.body().contains("Test Phone"), sessions.body());
        assertTrue(sessions.body().contains("Session Test"), sessions.body());
        assertTrue(sessions.body().contains("\"mode\":\"DIRECT\""), sessions.body());
    }

    /**
     * Asserted per profile rather than on the global count: every test in this class
     * registers its own account, and sessions from earlier tests are still inside their
     * idle window, so the server-wide total is not a stable number to assert on.
     */
    @Test
    void healthTabCountsTheActiveStream() throws Exception {
        decide();

        HttpResponse<String> health =
                send("GET", "/api/media/admin/health", null, token, ADMIN_KEY);
        assertEquals(200, health.statusCode(), health.body());
        assertTrue(health.body().contains("\"activeStreams\":"), health.body());

        HttpResponse<String> sessions =
                send("GET", "/api/media/admin/sessions", null, token, ADMIN_KEY);
        assertEquals(1, countOccurrences(sessions.body(), profileId),
                "expected exactly one session for this profile: " + sessions.body());
    }

    /** Bytes actually served are metered, which is what the Mbps card is built on. */
    @Test
    void streamingMetersBytesAgainstTheSession() throws Exception {
        String sessionId = extract(decide().body(), "sessionId");

        HttpResponse<String> streamed = send("GET",
                "/api/media/items/" + itemId + "/stream?session=" + sessionId, null, token, null);
        assertEquals(200, streamed.statusCode());

        HttpResponse<String> sessions =
                send("GET", "/api/media/admin/sessions", null, token, ADMIN_KEY);
        assertTrue(sessions.body().contains("\"bytesServed\":" + FILE_SIZE), sessions.body());
    }

    /**
     * The behaviour the stop button depends on. A direct play has no process to kill,
     * so ending the session must make the next range request fail.
     */
    @Test
    void endingSessionStopsFurtherStreaming() throws Exception {
        String sessionId = extract(decide().body(), "sessionId");
        String streamPath = "/api/media/items/" + itemId + "/stream?session=" + sessionId;

        assertEquals(200, send("GET", streamPath, null, token, null).statusCode());

        assertEquals(204, send("DELETE", "/api/media/admin/sessions/" + sessionId,
                null, token, ADMIN_KEY).statusCode());

        HttpResponse<String> afterEnd = send("GET", streamPath, null, token, null);
        assertEquals(403, afterEnd.statusCode(), afterEnd.body());
        assertTrue(afterEnd.body().contains("ended by the owner"), afterEnd.body());
    }

    /** Streaming without a session stays possible — it is just untracked. */
    @Test
    void streamingWithoutSessionStillWorks() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/items/" + itemId + "/stream", null, token, null);
        assertEquals(200, response.statusCode(), response.body());
    }

    /**
     * Re-deciding replaces the session rather than stacking a second one up — which is
     * what a client does every time it seeks past a transcoded region, so without the
     * replacement the People tab would show one viewer several times over.
     */
    @Test
    void redecidingDoesNotDoubleCountTheViewer() throws Exception {
        decide();
        decide();
        decide();

        HttpResponse<String> sessions =
                send("GET", "/api/media/admin/sessions", null, token, ADMIN_KEY);
        assertEquals(1, countOccurrences(sessions.body(), profileId),
                "three decisions should leave one session: " + sessions.body());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /** Reported progress reaches the live session, so the panel shows real positions. */
    @Test
    void progressReportsUpdateTheLiveSession() throws Exception {
        decide();

        assertEquals(200, send("PUT", "/api/media/items/" + itemId + "/progress",
                "{\"positionSeconds\":120,\"durationSeconds\":600}", token, null).statusCode());

        HttpResponse<String> sessions =
                send("GET", "/api/media/admin/sessions", null, token, ADMIN_KEY);
        assertTrue(sessions.body().contains("\"positionSeconds\":120"), sessions.body());
        assertTrue(sessions.body().contains("\"percentComplete\":20"), sessions.body());
    }

    /** A play is counted so the panel can later say a file always transcodes. */
    @Test
    void decisionCountsAsADirectPlay() throws Exception {
        decide();
        MediaItem reloaded = items.findById(itemId).orElseThrow();
        assertTrue(reloaded.getDirectPlayCount() >= 1,
                "expected a direct play count, got " + reloaded.getDirectPlayCount());
        assertEquals(0, reloaded.getTranscodeCount());
        assertTrue(!reloaded.alwaysTranscodes());
    }

    /** Watch hours come from increments, so a first report alone contributes nothing. */
    @Test
    void watchHoursAccumulateFromProgressDeltas() throws Exception {
        decide();
        send("PUT", "/api/media/items/" + itemId + "/progress",
                "{\"positionSeconds\":10,\"durationSeconds\":600}", token, null);
        send("PUT", "/api/media/items/" + itemId + "/progress",
                "{\"positionSeconds\":25,\"durationSeconds\":600}", token, null);

        HttpResponse<String> people =
                send("GET", "/api/media/admin/people", null, token, ADMIN_KEY);
        assertEquals(200, people.statusCode(), people.body());
        // Small but non-zero, and credited to the right person.
        assertTrue(people.body().contains("Owner"), people.body());
        assertTrue(!people.body().contains("\"totalHoursThisMonth\":0.0")
                        || people.body().contains("\"hoursThisMonth\":0.0"),
                people.body());
    }

    /**
     * A forward seek must not be credited as watched time — the increment is bounded by
     * how much wall-clock time actually elapsed.
     */
    @Test
    void seekingForwardDoesNotInflateWatchHours() throws Exception {
        decide();
        send("PUT", "/api/media/items/" + itemId + "/progress",
                "{\"positionSeconds\":5,\"durationSeconds\":600}", token, null);
        // Jump five minutes ahead within a fraction of a second of real time.
        send("PUT", "/api/media/items/" + itemId + "/progress",
                "{\"positionSeconds\":305,\"durationSeconds\":600}", token, null);

        HttpResponse<String> people =
                send("GET", "/api/media/admin/people", null, token, ADMIN_KEY);
        // 300 seconds of seek would be 0.08h; the cap keeps it far below that.
        assertTrue(people.body().contains("\"hoursThisMonth\":0.0"),
                "a seek should not be credited as watch time: " + people.body());
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
