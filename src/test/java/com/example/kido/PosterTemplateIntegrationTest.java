package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The ceremony poster module over real HTTP on H2.
 *
 * <p>Two things here are worth more than the CRUD: that a layout's component
 * references are resolved when a template is saved, and that a component cannot be
 * deleted out from under a template that uses it. Those are the two ways this catalog
 * can start rendering posters with holes in them, and neither is visible until
 * somebody opens the editor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PosterTemplateIntegrationTest {

    private static final String ADMIN_KEY = "test-admin-key";

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private String parentToken;
    private String childToken;

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
        builder.method(method, json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String extract(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private int num(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    /** How many objects in the page's items array, counted by their ids. */
    private int itemCount(String body) {
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"[^\"]+\"").matcher(body);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    private String register(String prefix, String role) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"%s_%s","email":"%s_%s@example.com","password":"pw123456",
                 "displayName":"Poster Tester","role":"%s"}
                """.formatted(prefix, unique, prefix, unique, role), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        return extract(registered.body(), "token");
    }

    /** A layout that references the given sticker, with everything else valid. */
    private String templateBody(String name, String category, String stickerId) {
        return """
                {"category":"%s","name":"%s","thumbnail":"https://example.test/t.jpg",
                 "layout":{"backgroundColor":"#FDF6EC","canvasWidth":1080,"canvasHeight":1350,
                   "textBoxes":[{"key":"title","label":"Title","text":"Hello",
                     "x":0.1,"y":0.1,"width":0.8,"height":0.1,"fontSize":0.05,
                     "color":"#7B1E3A","align":"center"}],
                   "imageSlots":[{"key":"photo","label":"Photo","x":0.2,"y":0.3,
                     "width":0.6,"height":0.3,"shape":"circle"}],
                   "stickers":[{"key":"corner","componentId":"%s","x":0.0,"y":0.0,
                     "width":0.2,"height":0.2,"rotation":0}]},
                 "colorThemes":[{"name":"Maroon & Gold","primary":"#7B1E3A","secondary":"#D4AF37"}]}
                """.formatted(category, name, stickerId);
    }

    @BeforeEach
    void setUp() throws Exception {
        parentToken = register("poster_owner", "PARENT");
        childToken = register("poster_kid", "CHILD");
    }

    @Test
    void seeded_categories_are_listed_and_fetched_by_category() throws Exception {
        HttpResponse<String> all = send("GET", "/api/poster/templates", null, childToken, null);
        assertEquals(200, all.statusCode(), all.body());
        assertTrue(all.body().contains("MARRIAGE"), all.body());
        assertTrue(all.body().contains("BIRTHDAY"));
        assertTrue(all.body().contains("BABY_SHOWER"));
        // app.poster.seed-count in the test properties; six per ceremony.
        assertEquals(42, num(all.body(), "totalItems"));

        // The URL form the app actually sends, and the layout comes back whole.
        HttpResponse<String> marriage = send("GET", "/api/poster/templates/marriage", null, childToken, null);
        assertEquals(200, marriage.statusCode(), marriage.body());
        assertEquals(6, num(marriage.body(), "totalItems"), marriage.body());
        assertTrue(marriage.body().contains("Wedding Invitation"), marriage.body());
        assertTrue(marriage.body().contains("\"backgroundColor\""));
        assertTrue(marriage.body().contains("\"textBoxes\""));
        assertFalse(marriage.body().contains("BIRTHDAY"));

        // Spelling of the category is forgiving; an unknown one names the valid values.
        assertEquals(200, send("GET", "/api/poster/templates/BABY_SHOWER", null, childToken, null).statusCode());
        assertEquals(200, send("GET", "/api/poster/templates/baby-shower", null, childToken, null).statusCode());
        HttpResponse<String> bogus = send("GET", "/api/poster/templates/weddingg", null, childToken, null);
        assertEquals(400, bogus.statusCode());
        assertTrue(bogus.body().contains("NAMING_CEREMONY"), bogus.body());

        // Components are one cached catalog, filterable by type.
        HttpResponse<String> components = send("GET", "/api/poster/components", null, childToken, null);
        assertEquals(200, components.statusCode());
        assertTrue(components.body().contains("FONT"));
        assertTrue(components.body().contains("STICKER"));
        HttpResponse<String> frames = send("GET", "/api/poster/components?type=frame", null, childToken, null);
        assertTrue(frames.body().contains("borderColor"), frames.body());
        assertFalse(frames.body().contains("\"type\":\"STICKER\""));
    }

    @Test
    void a_catalog_this_size_is_paged_and_can_be_asked_for_without_layouts() throws Exception {
        HttpResponse<String> first = send("GET", "/api/poster/templates?page=0&size=10", null, childToken, null);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(0, num(first.body(), "page"));
        assertEquals(10, num(first.body(), "size"));
        assertEquals(42, num(first.body(), "totalItems"));
        assertEquals(5, num(first.body(), "totalPages"));
        assertEquals(10, itemCount(first.body()));

        // A later page is different templates, not the same ones again.
        HttpResponse<String> second = send("GET", "/api/poster/templates?page=1&size=10", null, childToken, null);
        assertEquals(1, num(second.body(), "page"));
        assertFalse(second.body().contains(extract(first.body(), "id")), "page 1 repeated a row from page 0");

        // Past the end is an empty page rather than an error.
        HttpResponse<String> beyond = send("GET", "/api/poster/templates?page=99&size=10", null, childToken, null);
        assertEquals(200, beyond.statusCode());
        assertEquals(0, itemCount(beyond.body()));

        // size is clamped, not trusted: nobody gets the whole catalog in one response.
        assertEquals(100, num(send("GET", "/api/poster/templates?size=5000", null, childToken, null).body(), "size"));
        assertEquals(1, num(send("GET", "/api/poster/templates?size=0", null, childToken, null).body(), "size"));

        // The picker grid's view: everything but the layout, which is the bulk of it.
        HttpResponse<String> summary =
                send("GET", "/api/poster/templates/marriage?view=summary", null, childToken, null);
        assertEquals(200, summary.statusCode());
        assertEquals(6, num(summary.body(), "totalItems"));
        assertTrue(summary.body().contains("\"thumbnail\""));
        assertTrue(summary.body().contains("\"colorThemes\""));
        assertFalse(summary.body().contains("\"layout\""), summary.body());
        assertTrue(summary.body().length() * 3 < send("GET", "/api/poster/templates/marriage",
                null, childToken, null).body().length(), "a summary should be far smaller than the full page");
    }

    @Test
    void owner_creates_updates_and_deletes_a_template() throws Exception {
        String stickerId = extract(send("POST", "/api/poster/components", """
                {"type":"STICKER","name":"Test Peacock %s","url":"https://example.test/peacock.png"}
                """.formatted(UUID.randomUUID().toString().substring(0, 6)),
                parentToken, ADMIN_KEY).body(), "id");
        assertNotNull(stickerId);

        HttpResponse<String> created = send("POST", "/api/poster/templates",
                templateBody("Peacock Blue", "marriage", stickerId), parentToken, ADMIN_KEY);
        assertEquals(201, created.statusCode(), created.body());
        String id = extract(created.body(), "id");
        assertTrue(created.body().contains("\"category\":\"MARRIAGE\""));
        assertTrue(created.body().contains("\"categorySlug\":\"marriage\""));

        // Round-trips through jsonb and comes back as a layout, not a string.
        HttpResponse<String> fetched = send("GET", "/api/poster/templates/by-id/" + id, null, childToken, null);
        assertEquals(200, fetched.statusCode());
        assertTrue(fetched.body().contains("\"componentId\":\"" + stickerId + "\""), fetched.body());

        // PUT replaces the template whole, including its category.
        HttpResponse<String> updated = send("PUT", "/api/poster/templates/" + id,
                templateBody("Peacock Blue II", "engagement", stickerId), parentToken, ADMIN_KEY);
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(updated.body().contains("Peacock Blue II"));
        assertTrue(updated.body().contains("\"category\":\"ENGAGEMENT\""));
        assertFalse(send("GET", "/api/poster/templates/marriage", null, childToken, null)
                .body().contains("Peacock Blue II"));

        // A sticker in use cannot be deleted; the template can, and then it can.
        HttpResponse<String> refused = send("DELETE", "/api/poster/components/" + stickerId,
                null, parentToken, ADMIN_KEY);
        assertEquals(409, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("Peacock Blue II"), refused.body());

        assertEquals(204, send("DELETE", "/api/poster/templates/" + id, null, parentToken, ADMIN_KEY).statusCode());
        assertEquals(404, send("GET", "/api/poster/templates/by-id/" + id, null, childToken, null).statusCode());
        assertEquals(204, send("DELETE", "/api/poster/components/" + stickerId, null, parentToken, ADMIN_KEY).statusCode());
    }

    @Test
    void bad_templates_are_refused_before_they_reach_the_catalog() throws Exception {
        String sticker = extract(send("GET", "/api/poster/components?type=sticker", null, childToken, null).body(), "id");
        assertNotNull(sticker);

        // A category outside the enum
        assertEquals(400, send("POST", "/api/poster/templates",
                templateBody("Nope", "diwali", sticker), parentToken, ADMIN_KEY).statusCode());

        // A background that is not a colour
        assertEquals(400, send("POST", "/api/poster/templates",
                templateBody("Nope", "marriage", sticker).replace("#FDF6EC", "beige"),
                parentToken, ADMIN_KEY).statusCode());

        // A sticker id nobody has
        HttpResponse<String> dangling = send("POST", "/api/poster/templates",
                templateBody("Nope", "marriage", "no-such-component"), parentToken, ADMIN_KEY);
        assertEquals(400, dangling.statusCode(), dangling.body());
        assertTrue(dangling.body().contains("Unknown STICKER component"), dangling.body());

        // A real component used as the wrong kind: a font where a sticker belongs
        String fontId = extract(send("GET", "/api/poster/components?type=font", null, childToken, null).body(), "id");
        HttpResponse<String> wrongKind = send("POST", "/api/poster/templates",
                templateBody("Nope", "marriage", fontId), parentToken, ADMIN_KEY);
        assertEquals(400, wrongKind.statusCode(), wrongKind.body());
        assertTrue(wrongKind.body().contains("is a FONT"), wrongKind.body());

        // A font with no file to load
        assertEquals(400, send("POST", "/api/poster/components",
                "{\"type\":\"FONT\",\"name\":\"Ghost\"}", parentToken, ADMIN_KEY).statusCode());
    }

    @Test
    void writing_the_catalog_is_owner_only() throws Exception {
        String sticker = extract(send("GET", "/api/poster/components?type=sticker", null, childToken, null).body(), "id");
        String body = templateBody("Sneaky", "marriage", sticker);

        // A parent without the key, the key without a parent, and a child with both.
        assertEquals(403, send("POST", "/api/poster/templates", body, parentToken, null).statusCode());
        assertEquals(403, send("POST", "/api/poster/templates", body, childToken, ADMIN_KEY).statusCode());
        assertEquals(403, send("POST", "/api/poster/templates", body, parentToken, "wrong-key").statusCode());
        assertEquals(403, send("GET", "/api/poster/templates?includeDrafts=true", null, childToken, null).statusCode());

        // Reading needs a signed-in profile, but nothing more. No token is a 403 here
        // rather than a 401, as everywhere else in this app: the chain's default-deny
        // rule refuses the request before any authentication is attempted.
        assertEquals(403, send("GET", "/api/poster/templates", null, null, null).statusCode());
        assertEquals(200, send("GET", "/api/poster/templates", null, childToken, null).statusCode());
    }
}
