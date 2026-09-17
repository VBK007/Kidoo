package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
 * The language facet end to end: the schema Flyway builds, the backfill that fills it
 * from probes already on record, and the vocabulary endpoint above it.
 *
 * <p>Rows are inserted with a probe attached rather than scanned, because what is under
 * test is the derivation from {@code probe_audio_tracks} and its wiring — not ffprobe,
 * which has no files to read here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaLanguageIntegrationTest {

    private static final String ADMIN_KEY = "test-admin-key";

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
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

    /** The catalog is shared by the suite and this class asserts on the whole vocabulary. */
    @BeforeEach
    void setUp() throws Exception {
        profileId = null;
        items.deleteAll();

        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"lang_%s","email":"lang_%s@example.com","password":"pw123456",
                 "displayName":"Language Tester","role":"PARENT"}
                """.formatted(unique, unique), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        token = extract(registered.body(), "token");

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"Tester\",\"ageMode\":\"OLDER\"}", token, null);
        assertEquals(201, profile.statusCode(), profile.body());
        profileId = extract(profile.body(), "id");
    }

    /**
     * A row as it exists before this feature: probed, carrying its audio tracks, and
     * with no languages derived from them. This is what the backfill finds.
     */
    private MediaItem insertProbedFilm(String title, String audioTracks) {
        return items.save(MediaItem.builder()
                .type(MediaType.FILM)
                .filePath("D:/Media/" + title.replace(' ', '.') + ".mkv")
                .fileName(title.replace(' ', '.') + ".mkv")
                .fileSize(1_400_000_000L)
                .title(title)
                .sortTitle(title.toLowerCase())
                .mediaInfo(MediaInfo.builder()
                        .container("matroska,webm")
                        .videoCodec("h264")
                        .height(1080)
                        .audioTracks(audioTracks)
                        .probedAt(Instant.now())
                        .build())
                .build());
    }

    // --- persistence ---

    /** The collection table and its column have to survive a round trip through Flyway. */
    @Test
    void languagesPersistAndReadBack() {
        MediaItem film = insertProbedFilm("Dual", "0:eac3:tam:Tamil;1:aac:eng:English");
        film.getLanguages().addAll(List.of("ta", "en"));
        film.setPrimaryLanguage("ta");
        items.save(film);

        MediaItem reloaded = items.findById(film.getId()).orElseThrow();
        assertEquals(Set.of("ta", "en"), reloaded.getLanguages());
        assertEquals("ta", reloaded.getPrimaryLanguage());
    }

    // --- backfill ---

    /**
     * The point of the whole stage: a library indexed long before languages existed
     * gets them without a rescan, because the probe already held the answer.
     */
    @Test
    void backfillDerivesLanguagesFromProbesAlreadyOnRecord() throws Exception {
        MediaItem dual = insertProbedFilm("Dual", "0:eac3:tam:Tamil;1:aac:eng:English");
        MediaItem hindi = insertProbedFilm("Hindi Only", "0:aac:hin:Hindi");
        assertTrue(dual.getLanguages().isEmpty(), "starts with no languages");

        HttpResponse<String> backfilled = send("POST", "/api/media/admin/backfill-languages",
                null, token, ADMIN_KEY);
        assertEquals(200, backfilled.statusCode(), backfilled.body());
        assertTrue(backfilled.body().contains("\"moviesUpdated\":2"), backfilled.body());

        assertEquals(Set.of("ta", "en"),
                items.findById(dual.getId()).orElseThrow().getLanguages());
        // The first track is what a player picks, so it is the title's own language.
        assertEquals("ta", items.findById(dual.getId()).orElseThrow().getPrimaryLanguage());
        assertEquals("hi", items.findById(hindi.getId()).orElseThrow().getPrimaryLanguage());
    }

    /** Running it twice must not report work it did not do. */
    @Test
    void backfillIsIdempotent() throws Exception {
        insertProbedFilm("Dual", "0:eac3:tam:Tamil;1:aac:eng:English");

        send("POST", "/api/media/admin/backfill-languages", null, token, ADMIN_KEY);
        HttpResponse<String> again = send("POST", "/api/media/admin/backfill-languages",
                null, token, ADMIN_KEY);

        assertTrue(again.body().contains("\"moviesUpdated\":0"), again.body());
    }

    /** An unprobed file has no tracks to read, which is a known unknown, not a failure. */
    @Test
    void unprobedItemsAreLeftAlone() throws Exception {
        MediaItem unprobed = items.save(MediaItem.builder()
                .type(MediaType.FILM)
                .filePath("D:/Media/Unprobed.mkv")
                .fileName("Unprobed.mkv")
                .title("Unprobed")
                .sortTitle("unprobed")
                .build());

        HttpResponse<String> backfilled = send("POST", "/api/media/admin/backfill-languages",
                null, token, ADMIN_KEY);
        assertEquals(200, backfilled.statusCode(), backfilled.body());

        MediaItem reloaded = items.findById(unprobed.getId()).orElseThrow();
        assertTrue(reloaded.getLanguages().isEmpty());
        assertNull(reloaded.getPrimaryLanguage());
    }

    /** The panel can rewrite every row in the library, so it is owner-plus-key like the rest. */
    @Test
    void backfillIsOwnerOnly() throws Exception {
        assertEquals(403, send("POST", "/api/media/admin/backfill-languages",
                null, token, null).statusCode());
        assertEquals(403, send("POST", "/api/media/admin/backfill-languages",
                null, null, ADMIN_KEY).statusCode());
    }

    // --- vocabulary ---

    /**
     * What a chip row and, later, a language filter are checked against: a library can
     * only be searched for the languages it actually holds.
     */
    @Test
    void languagesEndpointListsWhatTheLibraryHoldsWithNames() throws Exception {
        insertProbedFilm("Dual", "0:eac3:tam:Tamil;1:aac:eng:English");
        insertProbedFilm("Hindi Only", "0:aac:hin:Hindi");
        send("POST", "/api/media/admin/backfill-languages", null, token, ADMIN_KEY);

        HttpResponse<String> response = send("GET", "/api/media/languages", null, token, null);
        assertEquals(200, response.statusCode(), response.body());

        // The name is resolved server-side so no client needs its own ISO table.
        assertTrue(response.body().contains("{\"code\":\"ta\",\"name\":\"Tamil\"}"),
                response.body());
        assertTrue(response.body().contains("{\"code\":\"hi\",\"name\":\"Hindi\"}"),
                response.body());
        assertTrue(response.body().contains("{\"code\":\"en\",\"name\":\"English\"}"),
                response.body());
    }

    /** A language nothing in the library speaks must not be offered as a filter. */
    @Test
    void languagesEndpointOmitsWhatTheLibraryDoesNotHold() throws Exception {
        insertProbedFilm("Hindi Only", "0:aac:hin:Hindi");
        send("POST", "/api/media/admin/backfill-languages", null, token, ADMIN_KEY);

        String body = send("GET", "/api/media/languages", null, token, null).body();
        assertTrue(body.contains("\"hi\""), body);
        assertFalse(body.contains("\"ta\""), body);
    }

    /** The detail view names the languages, so a person can see what they would hear. */
    @Test
    void itemDetailCarriesTheLanguages() throws Exception {
        MediaItem dual = insertProbedFilm("Dual", "0:eac3:tam:Tamil;1:aac:eng:English");
        send("POST", "/api/media/admin/backfill-languages", null, token, ADMIN_KEY);

        String body = send("GET", "/api/media/items/" + dual.getId(), null, token, null).body();
        assertTrue(body.contains("\"primaryLanguage\":\"ta\""), body);
        assertTrue(body.contains("\"languages\""), body);
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
