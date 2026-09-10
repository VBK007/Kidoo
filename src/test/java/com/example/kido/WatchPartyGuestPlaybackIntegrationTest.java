package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * What a guest token actually opens, against real files on disk.
 *
 * <p>Two films in the fixture rather than one, because the whole grant is the
 * difference between them: a guest admitted to the first must be able to play it and
 * must not be able to play the second, and a test with a single title cannot tell a
 * working check from a missing one.
 *
 * <p>Direct play throughout — the fixtures declare themselves as already-probed
 * H.264/AAC in MP4, so no ffmpeg is needed for the suite to run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WatchPartyGuestPlaybackIntegrationTest {

    private static final int FILE_SIZE = 4000;

    private static Path mediaRoot;
    private static Path partyFile;
    private static Path otherFile;

    @DynamicPropertySource
    static void configureMediaRoot(DynamicPropertyRegistry registry) {
        try {
            mediaRoot = Files.createTempDirectory("kido-party-root");

            Path partyFolder = Files.createDirectories(mediaRoot.resolve("Party Movie (2020)"));
            partyFile = partyFolder.resolve("Party.Movie.2020.1080p.mp4");
            Files.write(partyFile, payload());
            Files.write(partyFolder.resolve("poster.jpg"),
                    "FAKE-POSTER".getBytes(StandardCharsets.UTF_8));
            Files.writeString(partyFolder.resolve("Party.Movie.2020.1080p.en.srt"),
                    "1\r\n00:00:20,000 --> 00:00:24,400\r\nSubtitled line\r\n\r\n",
                    StandardCharsets.UTF_8);

            Path otherFolder = Files.createDirectories(mediaRoot.resolve("Other Movie (2021)"));
            otherFile = otherFolder.resolve("Other.Movie.2021.1080p.mp4");
            Files.write(otherFile, payload());
        } catch (IOException ex) {
            throw new IllegalStateException("Could not build the party fixture", ex);
        }
        registry.add("app.media.roots", () -> mediaRoot.toString());
    }

    private static byte[] payload() {
        byte[] bytes = new byte[FILE_SIZE];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 256);
        }
        return bytes;
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
                    // Temp directory; a leftover file is not worth failing a test over.
                }
            });
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    private final HttpClient http = HttpClient.newHttpClient();

    private String hostToken;
    private String hostProfileId;
    private String partyItemId;
    private String otherItemId;
    private String code;
    private String guestToken;

    @BeforeEach
    void setUp() throws Exception {
        String[] host = registerParentWithProfile("play_host", "Amma");
        hostToken = host[0];
        hostProfileId = host[1];

        partyItemId = findOrCreate(partyFile, "Party Movie", 2020).getId();
        otherItemId = findOrCreate(otherFile, "Other Movie", 2021).getId();

        HttpResponse<String> created = send("POST", "/api/parties",
                "{\"mediaItemId\":\"%s\"}".formatted(partyItemId), hostToken, hostProfileId);
        assertEquals(201, created.statusCode(), created.body());
        code = extract(created.body(), "code");
        guestToken = admittedGuestToken("Ravi");
    }

    // --- the happy path ---

    @Test
    void aGuestGetsADecisionAndCanPullTheBytes() throws Exception {
        HttpResponse<String> decided = send("POST",
                "/api/media/guest/playback-decision", capabilities(), guestToken, null);
        assertEquals(200, decided.statusCode(), decided.body());
        assertTrue(decided.body().contains("\"mode\":\"DIRECT\""), decided.body());
        assertEquals(partyItemId, extract(decided.body(), "mediaItemId"));

        String url = extract(decided.body(), "url");
        assertNotNull(url, decided.body());

        HttpResponse<byte[]> streamed = http.send(
                request("GET", url, null, guestToken, null).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, streamed.statusCode());
        assertEquals(FILE_SIZE, streamed.body().length);
    }

    @Test
    void aGuestCanReadTheSubtitlesAndPosterOfTheirOwnTitle() throws Exception {
        HttpResponse<String> subtitle = send("GET",
                "/api/media/items/" + partyItemId + "/subtitles/0", null, guestToken, null);
        assertEquals(200, subtitle.statusCode(), subtitle.body());
        assertTrue(subtitle.body().contains("Subtitled line"), subtitle.body());

        assertEquals(200, send("GET", "/api/media/items/" + partyItemId + "/poster",
                null, guestToken, null).statusCode());
    }

    // --- the grant ---

    @Test
    void aGuestCannotTouchAnyOtherTitle() throws Exception {
        assertEquals(403, send("GET", "/api/media/items/" + otherItemId + "/stream",
                null, guestToken, null).statusCode());
        assertEquals(403, send("GET", "/api/media/items/" + otherItemId + "/subtitles/0",
                null, guestToken, null).statusCode());
        assertEquals(403, send("GET", "/api/media/items/" + otherItemId + "/poster",
                null, guestToken, null).statusCode());
        assertEquals(403, send("GET", "/api/media/items/" + otherItemId + "/backdrop",
                null, guestToken, null).statusCode());
    }

    @Test
    void aGuestCannotUseTheAccountPlaybackEndpoints() throws Exception {
        // Profile-scoped, and a guest has no profile. Refused by the security chain
        // rather than blowing up inside a handler expecting an AppUser.
        assertEquals(403, send("POST",
                "/api/media/items/" + partyItemId + "/playback-decision",
                capabilities(), guestToken, null).statusCode());
        assertEquals(403, send("GET", "/api/media/items/" + partyItemId + "/media-info",
                null, guestToken, null).statusCode());
    }

    @Test
    void endingThePartyRevokesTheStreamEvenThoughTheTokenIsStillValid() throws Exception {
        assertEquals(200, send("GET", "/api/media/items/" + partyItemId + "/stream",
                null, guestToken, null).statusCode());

        assertEquals(204, send("DELETE", "/api/parties/" + code,
                null, hostToken, hostProfileId).statusCode());

        HttpResponse<String> after = send("GET", "/api/media/items/" + partyItemId + "/stream",
                null, guestToken, null);
        assertEquals(403, after.statusCode(), after.body());
        assertTrue(after.body().contains("ended"), after.body());
    }

    // --- the refactor did not move anything ---

    @Test
    void anAccountHolderStillPlaysThroughTheOrdinaryEndpoint() throws Exception {
        HttpResponse<String> decided = send("POST",
                "/api/media/items/" + partyItemId + "/playback-decision",
                capabilities(), hostToken, hostProfileId);
        assertEquals(200, decided.statusCode(), decided.body());
        assertTrue(decided.body().contains("\"mode\":\"DIRECT\""), decided.body());

        HttpResponse<byte[]> streamed = http.send(
                request("GET", extract(decided.body(), "url"), null, hostToken, hostProfileId).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, streamed.statusCode());
        assertEquals(FILE_SIZE, streamed.body().length);
    }

    @Test
    void aGuestStreamIsVisibleToTheOwnerAndNamedAsAGuest() throws Exception {
        send("POST", "/api/media/guest/playback-decision", capabilities(), guestToken, null);

        String sessions = adminSessions();
        // A guest costs the same upstream as anyone else, so the owner has to be able
        // to see them and end them.
        assertTrue(sessions.contains("Ravi (guest)"), sessions);
    }

    // --- ending the party stops what it was paying for ---

    @Test
    void endingThePartyTerminatesTheGuestStreamButNotTheMembersOwn() throws Exception {
        send("POST", "/api/media/guest/playback-decision", capabilities(), guestToken, null);
        send("POST", "/api/media/items/" + partyItemId + "/playback-decision",
                capabilities(), hostToken, hostProfileId);

        String before = adminSessions();
        assertTrue(sessionFor(before, "Ravi (guest)").contains("\"terminated\":false"), before);
        assertTrue(sessionFor(before, "Amma").contains("\"terminated\":false"), before);

        assertEquals(204, send("DELETE", "/api/parties/" + code,
                null, hostToken, hostProfileId).statusCode());

        String after = adminSessions();
        // The guest had no entitlement of their own, so their stream stops at once —
        // which is what frees an ffmpeg process rather than waiting for the timeout.
        assertTrue(sessionFor(after, "Ravi (guest)").contains("\"terminated\":true"), after);
        // The host could have played this title without any party, so the server does
        // not overrule an entitlement it never granted.
        assertTrue(sessionFor(after, "Amma").contains("\"terminated\":false"), after);
    }

    private String adminSessions() throws Exception {
        HttpRequest.Builder builder = request("GET", "/api/media/admin/sessions",
                null, hostToken, hostProfileId);
        builder.header("X-Admin-Key", "test-admin-key");
        HttpResponse<String> sessions = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, sessions.statusCode(), sessions.body());
        return sessions.body();
    }

    /** Slices one session object out of the admin array by the name it is filed under. */
    private static String sessionFor(String json, String profileName) {
        for (String chunk : json.split("\\},\\{")) {
            if (chunk.contains("\"profileName\":\"" + profileName + "\"")) {
                return chunk;
            }
        }
        throw new AssertionError("no session for '" + profileName + "' in " + json);
    }

    // --- plumbing ---

    private static String capabilities() {
        return """
                {"deviceName":"Ravi phone","videoCodecs":["h264"],"audioCodecs":["aac"],
                 "containers":["mp4"],"maxHeight":1080,"supportsHls":true}
                """;
    }

    private String admittedGuestToken(String name) throws Exception {
        HttpResponse<String> knocked = send("POST", "/api/parties/" + code + "/guest",
                "{\"name\":\"%s\"}".formatted(name), null, null);
        assertEquals(200, knocked.statusCode(), knocked.body());
        String requestId = extract(knocked.body(), "requestId");

        assertEquals(200, send("POST", "/api/parties/" + code + "/admit",
                "{\"requestId\":\"%s\",\"allow\":true}".formatted(requestId),
                hostToken, hostProfileId).statusCode());

        HttpResponse<String> polled = send("GET", "/api/parties/" + code + "/guest/" + requestId
                        + "?token=" + java.net.URLEncoder.encode(
                                extract(knocked.body(), "pollToken"), StandardCharsets.UTF_8),
                null, null, null);
        String token = extract(polled.body(), "token");
        assertNotNull(token, polled.body());
        return token;
    }

    private MediaItem findOrCreate(Path file, String title, int year) {
        String path = file.toAbsolutePath().normalize().toString();
        return items.findByFilePath(path).orElseGet(() -> items.save(MediaItem.builder()
                .type(MediaType.FILM)
                .filePath(path)
                .fileName(file.getFileName().toString())
                .folderPath(file.getParent().toString())
                .fileSize(FILE_SIZE)
                .title(title)
                .sortTitle(title.toLowerCase())
                .year(year)
                .posterPath(file.getParent().resolve("poster.jpg").toString())
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
    }

    private HttpResponse<String> send(String method, String path, String json,
                                      String bearer, String profile) throws Exception {
        return http.send(request(method, path, json, bearer, profile).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String method, String path, String json,
                                        String bearer, String profile) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path));
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
        return builder;
    }

    /** Parent, because the admin panel is owner-only and one test reads it. */
    private String[] registerParentWithProfile(String prefix, String profileName) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"%s_%s","email":"%s_%s@example.com","password":"pw123456",
                 "displayName":"Playback Tester","role":"PARENT"}
                """.formatted(prefix, unique, prefix, unique), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        String bearer = extract(registered.body(), "token");

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"%s\",\"ageMode\":\"OLDER\"}".formatted(profileName), bearer, null);
        assertEquals(201, profile.statusCode(), profile.body());
        return new String[]{bearer, extract(profile.body(), "id")};
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
