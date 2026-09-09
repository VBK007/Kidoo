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
import com.example.kido.media.catalog.MetadataSource;

/**
 * Covers the admin panel's gating and the shape of its three tabs.
 *
 * <p>The gating tests matter most: the panel can end other people's streams and purge
 * caches, so "owner only" has to mean it. Session lifecycle against a real file is
 * covered separately in {@code MediaAdminSessionIntegrationTest}, which needs a
 * configured library.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaAdminIntegrationTest {

    private static final String ADMIN_KEY = "test-admin-key";

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    private final HttpClient http = HttpClient.newHttpClient();
    private String parentToken;
    private String childToken;
    private String profileId;

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

    private String register(String prefix, String role) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"%s_%s","email":"%s_%s@example.com","password":"pw123456",
                 "displayName":"Admin Tester","role":"%s"}
                """.formatted(prefix, unique, prefix, unique, role), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        return extract(registered.body(), "token");
    }

    @BeforeEach
    void setUp() throws Exception {
        profileId = null;
        parentToken = register("owner", "PARENT");
        childToken = register("kid", "CHILD");
        assertNotNull(parentToken);

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"Owner\",\"ageMode\":\"OLDER\"}", parentToken, null);
        assertEquals(201, profile.statusCode(), profile.body());
        profileId = extract(profile.body(), "id");
    }

    private MediaItem insertFilm(String title, long sizeBytes) {
        return items.save(MediaItem.builder()
                .type(MediaType.FILM)
                .filePath("D:/Media/" + title.replace(' ', '.') + ".mkv")
                .fileName(title.replace(' ', '.') + ".mkv")
                .fileSize(sizeBytes)
                .title(title)
                .sortTitle(title.toLowerCase())
                .metadataSource(MetadataSource.NFO)
                .mediaInfo(MediaInfo.builder()
                        .container("matroska,webm")
                        .videoCodec("h264")
                        .audioCodecs("aac")
                        .height(1080)
                        .durationSeconds(7200.0)
                        .probedAt(Instant.now())
                        .build())
                .build());
    }

    // --- gating ---

    @Test
    void adminPanelRejectsMissingKey() throws Exception {
        assertEquals(403, send("GET", "/api/media/admin/health", null, parentToken, null)
                .statusCode());
        assertEquals(403, send("GET", "/api/media/admin/people", null, parentToken, null)
                .statusCode());
        assertEquals(403, send("GET", "/api/media/admin/disk", null, parentToken, null)
                .statusCode());
    }

    @Test
    void adminPanelRejectsWrongKey() throws Exception {
        assertEquals(403, send("GET", "/api/media/admin/health", null, parentToken, "nope")
                .statusCode());
    }

    /** The key alone must not be enough — a child account holding it is still not the owner. */
    @Test
    void adminPanelRejectsChildAccountEvenWithValidKey() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/admin/health", null, childToken, ADMIN_KEY);
        assertEquals(403, response.statusCode(), response.body());
    }

    @Test
    void adminPanelRejectsAnonymousCaller() throws Exception {
        assertEquals(403, send("GET", "/api/media/admin/health", null, null, ADMIN_KEY)
                .statusCode());
    }

    @Test
    void adminPanelAllowsParentWithKey() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/admin/health", null, parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void purgingCachesIsAlsoOwnerOnly() throws Exception {
        assertEquals(403, send("DELETE", "/api/media/admin/disk/caches", null, childToken, ADMIN_KEY)
                .statusCode());
        assertEquals(403, send("DELETE", "/api/media/admin/disk/caches", null, parentToken, null)
                .statusCode());
    }

    // --- Health tab ---

    @Test
    void healthTabReportsUptimeAndSevenDayChart() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/admin/health", null, parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());

        assertTrue(response.body().contains("\"uptimeSeconds\":"), response.body());
        assertTrue(response.body().contains("\"activeStreams\":0"), response.body());
        assertTrue(response.body().contains("\"watchWeek\":"), response.body());

        // Exactly seven bars, so the client never has to invent a gap.
        int days = countOccurrences(response.body(), "\"hours\":");
        assertEquals(7, days, "expected 7 chart days, got " + days + " in " + response.body());
    }

    @Test
    void healthTabReportsTranscodeCapacity() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/admin/health", null, parentToken, ADMIN_KEY);
        assertTrue(response.body().contains("\"activeTranscodes\":0"), response.body());
        assertTrue(response.body().contains("\"maxTranscodes\":"), response.body());
        assertTrue(response.body().contains("\"atCapacity\":false"), response.body());
    }

    /** A file that has only ever transcoded should surface as something to look at. */
    @Test
    void needsALookFlagsFilesThatAlwaysTranscode() throws Exception {
        MediaItem item = insertFilm("Always Transcodes", 8_000_000_000L);
        item.setTranscodeCount(5);
        item.setDirectPlayCount(0);
        items.save(item);

        HttpResponse<String> response =
                send("GET", "/api/media/admin/health", null, parentToken, ADMIN_KEY);
        assertTrue(response.body().contains("ALWAYS_TRANSCODES"), response.body());
    }

    @Test
    void needsALookFlagsFilenameGuessedMetadata() throws Exception {
        MediaItem item = insertFilm("Guessed Title", 1_000_000_000L);
        item.setMetadataSource(MetadataSource.FILENAME);
        items.save(item);

        HttpResponse<String> response =
                send("GET", "/api/media/admin/health", null, parentToken, ADMIN_KEY);
        assertTrue(response.body().contains("WRONG_MATCHES"), response.body());
    }

    @Test
    void needsALookFlagsMissingFiles() throws Exception {
        MediaItem item = insertFilm("Vanished", 1_000_000_000L);
        item.setMissing(true);
        items.save(item);

        HttpResponse<String> response =
                send("GET", "/api/media/admin/health", null, parentToken, ADMIN_KEY);
        assertTrue(response.body().contains("MISSING_FILES"), response.body());
    }

    // --- People tab ---

    @Test
    void peopleTabListsProfilesWithHabitLines() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/admin/people", null, parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"liveSessions\":[]"), response.body());
        assertTrue(response.body().contains("Owner"), response.body());
        // Plain-English rather than a raw number, per the brief's copy tone.
        assertTrue(response.body().contains("nothing watched this month"), response.body());
    }

    /** No evidence, no advice: the suggestion card stays empty on a quiet server. */
    @Test
    void peopleTabOmitsSuggestionWithoutEvidence() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/admin/people", null, parentToken, ADMIN_KEY);
        assertTrue(response.body().contains("\"suggestion\":null"), response.body());
    }

    @Test
    void peopleTabSuggestsReencodingWhenSeveralAlwaysTranscode() throws Exception {
        for (int i = 0; i < 3; i++) {
            MediaItem item = insertFilm("Heavy File " + i, 9_000_000_000L);
            item.setTranscodeCount(4);
            item.setDirectPlayCount(0);
            items.save(item);
        }
        HttpResponse<String> response =
                send("GET", "/api/media/admin/people", null, parentToken, ADMIN_KEY);
        assertTrue(response.body().contains("REENCODE_ALWAYS_TRANSCODES"), response.body());
    }

    @Test
    void endingUnknownSessionIsNotFound() throws Exception {
        assertEquals(404, send("DELETE", "/api/media/admin/sessions/nope", null,
                parentToken, ADMIN_KEY).statusCode());
    }

    // --- Disk tab ---

    @Test
    void diskTabBreaksUsageDownByCategory() throws Exception {
        insertFilm("Big One", 8_000_000_000L);

        HttpResponse<String> response =
                send("GET", "/api/media/admin/disk", null, parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"categories\":"), response.body());
        assertTrue(response.body().contains("\"label\":\"Films\""), response.body());
        assertTrue(response.body().contains("\"label\":\"Ours\""), response.body());
        assertTrue(response.body().contains("Big One"), response.body());
    }

    @Test
    void diskTabAnnotatesBiggestFiles() throws Exception {
        MediaItem transcoder = insertFilm("Cpu Burner", 9_500_000_000L);
        transcoder.setTranscodeCount(3);
        transcoder.setDirectPlayCount(0);
        items.save(transcoder);

        HttpResponse<String> response =
                send("GET", "/api/media/admin/disk?biggestFiles=5", null, parentToken, ADMIN_KEY);
        assertTrue(response.body().contains("always transcodes"), response.body());
        assertTrue(response.body().contains("\"alwaysTranscodes\":true"), response.body());
    }

    @Test
    void diskTabSeparatesSafeCachesFromJudgementCalls() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/admin/disk", null, parentToken, ADMIN_KEY);
        assertTrue(response.body().contains("\"transcodeCacheBytes\":"), response.body());
        assertTrue(response.body().contains("\"trickplayCacheBytes\":"), response.body());
        assertTrue(response.body().contains("\"watchedByEveryoneBytes\":"), response.body());
    }

    @Test
    void purgingCachesReportsWhatItFreed() throws Exception {
        HttpResponse<String> response =
                send("DELETE", "/api/media/admin/disk/caches", null, parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"freedBytes\":"), response.body());
        assertTrue(response.body().contains("rebuild on demand"), response.body());
    }

    /** No libraries are configured in tests, so there is no volume to report. */
    @Test
    void diskTabReportsNoVolumesWithoutLibraries() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/admin/disk", null, parentToken, ADMIN_KEY);
        assertTrue(response.body().contains("\"volumes\":[]"), response.body());
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

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
