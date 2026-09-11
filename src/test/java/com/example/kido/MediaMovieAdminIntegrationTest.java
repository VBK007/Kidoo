package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;

/**
 * Drives the one movie upsert endpoint over real HTTP against in-memory H2.
 *
 * <p>No media library is configured in the test profile, which is the interesting case
 * rather than a limitation: it is exactly the shape of a server whose titles are typed
 * in rather than scanned off a disk, so everything here runs with no file behind it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaMovieAdminIntegrationTest {

    private static final String ADMIN_KEY = "test-admin-key";
    private static final String BOUNDARY = "kidoTestBoundary8f2c";

    /** A real 1x1 PNG, so the upload is decoded by the same path a poster would be. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGA"
                    + "hKmMIQAAAABJRU5ErkJggg==");

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    private final HttpClient http = HttpClient.newHttpClient();
    private String parentToken;
    private String childToken;
    private String profileId;

    // --- helpers ----------------------------------------------------------

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> send(String method, String path, String json,
                                      String bearer, String adminKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path));
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

    /** Posts the JSON part and one file part, the way a browser form would. */
    private HttpResponse<byte[]> sendMultipart(String movieJson, String field,
                                               String filename, String contentType,
                                               byte[] file, String bearer, String adminKey)
            throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"movie\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + movieJson + "\r\n").getBytes(StandardCharsets.UTF_8));
        if (file != null) {
            body.write(("--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + field
                    + "\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(file);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create(base() + "/api/media/admin/movies"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (adminKey != null) {
            builder.header("X-Admin-Key", adminKey);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> getBytes(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (profileId != null) {
            builder.header("X-Profile-Id", profileId);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String str(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private String array(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private boolean flag(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)").matcher(body);
        return m.find() && Boolean.parseBoolean(m.group(1));
    }

    private String register(String prefix, String role) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"%s_%s","email":"%s_%s@example.com",
                 "password":"pw123456","displayName":"Owner","role":"%s"}
                """.formatted(prefix, unique, prefix, unique, role), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        return str(registered.body(), "token");
    }

    @BeforeEach
    void setUp() throws Exception {
        profileId = null;
        parentToken = register("owner", "PARENT");
        childToken = register("kid", "CHILD");

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"Owner\",\"ageMode\":\"OLDER\"}", parentToken, null);
        assertEquals(201, profile.statusCode(), profile.body());
        profileId = str(profile.body(), "id");
    }

    private String createMovie(String title) throws Exception {
        HttpResponse<String> created = send("POST", "/api/media/admin/movies",
                "{\"title\":\"" + title + "\"}", parentToken, ADMIN_KEY);
        assertEquals(201, created.statusCode(), created.body());
        return str(created.body(), "id");
    }

    // --- inserting ---------------------------------------------------------

    @Test
    void a_movie_is_created_from_json_with_every_field_the_form_sets() throws Exception {
        String payload = """
                {"title":"Seven Samurai",
                 "originalTitle":"Shichinin no samurai",
                 "year":1954,
                 "plot":"A village hires seven warriors to defend the harvest.",
                 "tagline":"The mighty warriors who became the seven national heroes.",
                 "runtimeMinutes":207,
                 "rating":8.6,
                 "certification":"PG",
                 "genres":["Drama","Action"],
                 "directors":["Akira Kurosawa"],
                 "actors":["Toshiro Mifune","Takashi Shimura","Keiko Tsushima"],
                 "studio":"Toho",
                 "releaseDate":"1954-04-26",
                 "tmdbId":"346",
                 "imdbId":"tt0047478",
                 "quality":"1080p"}
                """;
        HttpResponse<String> created = send("POST", "/api/media/admin/movies", payload,
                parentToken, ADMIN_KEY);
        assertEquals(201, created.statusCode(), created.body());

        String body = created.body();
        assertNotNull(str(body, "id"));
        assertTrue(flag(body, "created"));
        assertEquals("Seven Samurai", str(body, "title"));
        assertEquals("FILM", str(body, "type"));
        assertEquals("seven samurai", str(body, "sortTitle"));
        assertEquals("1954-04-26", str(body, "releaseDate"));
        assertEquals("MANUAL", str(body, "metadataSource"));

        // The cast round-trips as a list, in the order it was sent.
        assertEquals("\"Toshiro Mifune\",\"Takashi Shimura\",\"Keiko Tsushima\"",
                array(body, "actors"));
        assertEquals("\"Akira Kurosawa\"", array(body, "directors"));

        // No file was named, so it is catalogued rather than pointed at a disk.
        assertTrue(flag(body, "missing"));
        MediaItem saved = items.findById(str(body, "id")).orElseThrow();
        assertTrue(saved.isCatalogOnly());
        assertEquals("Toshiro Mifune, Takashi Shimura, Keiko Tsushima", saved.getCastMembers());
    }

    /**
     * The point of the catalog-only flag: a title with no file has to survive the
     * reconciliation sweep, which otherwise marks every unseen path missing.
     */
    @Test
    void a_catalogued_title_browses_like_any_other() throws Exception {
        createMovie("Yojimbo");

        HttpResponse<String> browse = send("GET", "/api/media/items?q=Yojimbo", null,
                parentToken, null);
        assertEquals(200, browse.statusCode(), browse.body());
        assertTrue(browse.body().contains("Yojimbo"), browse.body());
    }

    // --- updating ----------------------------------------------------------

    @Test
    void the_same_endpoint_updates_when_an_id_is_sent() throws Exception {
        String id = createMovie("Rashomon");

        HttpResponse<String> updated = send("POST", "/api/media/admin/movies",
                "{\"id\":\"" + id + "\",\"plot\":\"Four accounts of one killing.\","
                        + "\"year\":1950}", parentToken, ADMIN_KEY);
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(!flag(updated.body(), "created"));
        assertEquals("Four accounts of one killing.", str(updated.body(), "plot"));
        assertEquals("Rashomon", str(updated.body(), "title"));
    }

    /** A form that edits one field sends one field, and must not wipe the rest. */
    @Test
    void an_omitted_field_is_left_alone_and_an_empty_one_is_cleared() throws Exception {
        String id = createMovie("Ikiru");
        send("POST", "/api/media/admin/movies",
                "{\"id\":\"" + id + "\",\"plot\":\"A bureaucrat builds a playground.\","
                        + "\"tagline\":\"To live.\",\"actors\":[\"Takashi Shimura\"]}",
                parentToken, ADMIN_KEY);

        // Touch only the year: plot, tagline and cast are not in the payload at all.
        HttpResponse<String> narrow = send("POST", "/api/media/admin/movies",
                "{\"id\":\"" + id + "\",\"year\":1952}", parentToken, ADMIN_KEY);
        assertEquals(200, narrow.statusCode(), narrow.body());
        assertEquals("A bureaucrat builds a playground.", str(narrow.body(), "plot"));
        assertEquals("\"Takashi Shimura\"", array(narrow.body(), "actors"));

        // An explicit empty value is a clear, which is the other half of the rule.
        HttpResponse<String> cleared = send("POST", "/api/media/admin/movies",
                "{\"id\":\"" + id + "\",\"tagline\":\"\",\"actors\":[]}",
                parentToken, ADMIN_KEY);
        assertEquals(200, cleared.statusCode(), cleared.body());
        assertEquals("", array(cleared.body(), "actors"));
        assertNotNull(items.findById(id).orElseThrow());
        assertTrue(items.findById(id).orElseThrow().getCastMembers() == null);
    }

    @Test
    void updating_an_unknown_id_is_not_found() throws Exception {
        HttpResponse<String> missing = send("POST", "/api/media/admin/movies",
                "{\"id\":\"no-such-movie\",\"title\":\"Ghost\"}", parentToken, ADMIN_KEY);
        assertEquals(404, missing.statusCode(), missing.body());
    }

    // --- the thumbnail ------------------------------------------------------

    @Test
    void a_thumbnail_uploaded_with_the_form_is_stored_and_served_back() throws Exception {
        HttpResponse<byte[]> created = sendMultipart(
                "{\"title\":\"High and Low\",\"year\":1963}",
                "poster", "poster.png", "image/png", PNG, parentToken, ADMIN_KEY);
        String body = new String(created.body(), StandardCharsets.UTF_8);
        assertEquals(201, created.statusCode(), body);

        assertTrue(flag(body, "hasPoster"), body);
        String id = str(body, "id");
        assertEquals("/api/media/items/" + id + "/poster", str(body, "posterUrl"));

        // And the bytes really come back from the URL the response handed out.
        HttpResponse<byte[]> fetched = getBytes("/api/media/items/" + id + "/poster",
                parentToken);
        assertEquals(200, fetched.statusCode());
        assertEquals(PNG.length, fetched.body().length);
    }

    @Test
    void a_thumbnail_can_be_added_to_a_title_that_already_exists() throws Exception {
        String id = createMovie("Stray Dog");

        HttpResponse<byte[]> updated = sendMultipart(
                "{\"id\":\"" + id + "\"}", "poster", "p.png", "image/png", PNG,
                parentToken, ADMIN_KEY);
        assertEquals(200, updated.statusCode());
        assertTrue(flag(new String(updated.body(), StandardCharsets.UTF_8), "hasPoster"));
        assertEquals(200, getBytes("/api/media/items/" + id + "/poster", parentToken)
                .statusCode());
    }

    @Test
    void a_file_that_is_not_an_image_is_refused() throws Exception {
        HttpResponse<byte[]> refused = sendMultipart(
                "{\"title\":\"Not a poster\"}", "poster", "notes.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8), parentToken, ADMIN_KEY);
        assertEquals(415, refused.statusCode(),
                new String(refused.body(), StandardCharsets.UTF_8));
    }

    // --- validation ---------------------------------------------------------

    @Test
    void a_new_title_needs_a_title_and_sane_values() throws Exception {
        assertEquals(400, send("POST", "/api/media/admin/movies", "{}",
                parentToken, ADMIN_KEY).statusCode());
        assertEquals(400, send("POST", "/api/media/admin/movies",
                "{\"title\":\"Bad rating\",\"rating\":11.5}",
                parentToken, ADMIN_KEY).statusCode());
        assertEquals(400, send("POST", "/api/media/admin/movies",
                "{\"title\":\"Bad type\",\"type\":\"DOCUMENTARY\"}",
                parentToken, ADMIN_KEY).statusCode());
        assertEquals(400, send("POST", "/api/media/admin/movies",
                "{\"title\":\"Bad date\",\"releaseDate\":\"26-04-1954\"}",
                parentToken, ADMIN_KEY).statusCode());
    }

    /** No library is configured here, so any path at all is outside one. */
    @Test
    void a_file_path_outside_the_media_library_is_refused() throws Exception {
        HttpResponse<String> refused = send("POST", "/api/media/admin/movies",
                "{\"title\":\"Traversal\",\"filePath\":\"/etc/passwd\"}",
                parentToken, ADMIN_KEY);
        assertTrue(refused.statusCode() == 403 || refused.statusCode() == 404,
                "expected a refusal, got " + refused.statusCode() + " " + refused.body());
    }

    // --- who may call it ----------------------------------------------------

    @Test
    void it_takes_a_parent_account_and_the_admin_key() throws Exception {
        String payload = "{\"title\":\"Gatekeeping\"}";

        // Signed in as a parent, but no key.
        assertEquals(403, send("POST", "/api/media/admin/movies", payload,
                parentToken, null).statusCode());
        // The key, but a child account.
        assertEquals(403, send("POST", "/api/media/admin/movies", payload,
                childToken, ADMIN_KEY).statusCode());
        // The key alone, with no account at all.
        assertEquals(403, send("POST", "/api/media/admin/movies", payload,
                null, ADMIN_KEY).statusCode());
    }
}
