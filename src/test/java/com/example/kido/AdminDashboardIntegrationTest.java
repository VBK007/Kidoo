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

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.catalog.MetadataSource;

/**
 * Covers the web admin dashboard: its gating, and that the numbers on it move when
 * the thing they count does.
 *
 * <p>Assertions are relative — "one more account than before", not "seven accounts".
 * The suite shares one H2 database, so any absolute figure here would be a test that
 * fails when an unrelated one registers a user.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminDashboardIntegrationTest {

    private static final String ADMIN_KEY = "test-admin-key";

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

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

    private String register(String prefix, String role) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"%s_%s","email":"%s_%s@example.com","password":"pw123456",
                 "displayName":"Dashboard Tester","role":"%s"}
                """.formatted(prefix, unique, prefix, unique, role), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        return extract(registered.body(), "token");
    }

    @BeforeEach
    void setUp() throws Exception {
        parentToken = register("dashowner", "PARENT");
        childToken = register("dashkid", "CHILD");
        assertNotNull(parentToken);
    }

    // --- gating ---

    @Test
    void dashboardRejectsMissingKey() throws Exception {
        assertEquals(403, send("GET", "/api/admin/dashboard", null, parentToken, null).statusCode());
        assertEquals(403, send("GET", "/api/admin/users", null, parentToken, null).statusCode());
        assertEquals(403, send("GET", "/api/admin/applications", null, parentToken, null).statusCode());
        assertEquals(403, send("GET", "/api/admin/catalog", null, parentToken, null).statusCode());
        assertEquals(403, send("GET", "/api/admin/engagement", null, parentToken, null).statusCode());
    }

    @Test
    void dashboardRejectsWrongKey() throws Exception {
        assertEquals(403, send("GET", "/api/admin/dashboard", null, parentToken, "nope").statusCode());
    }

    /** The key alone must not be enough — a child account holding it is not the owner. */
    @Test
    void dashboardRejectsChildAccountEvenWithValidKey() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/admin/dashboard", null, childToken, ADMIN_KEY);
        assertEquals(403, response.statusCode(), response.body());
    }

    @Test
    void dashboardRejectsAnonymousCaller() throws Exception {
        assertEquals(403, send("GET", "/api/admin/dashboard", null, null, ADMIN_KEY).statusCode());
    }

    // --- shape ---

    @Test
    void dashboardReturnsEverySection() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/admin/dashboard", null, parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());

        String body = response.body();
        assertTrue(body.contains("\"generatedAt\":"), body);
        assertTrue(body.contains("\"users\":"), body);
        assertTrue(body.contains("\"applications\":"), body);
        assertTrue(body.contains("\"catalog\":"), body);
        assertTrue(body.contains("\"engagement\":"), body);
    }

    // --- users ---

    @Test
    void registeringAnAccountRaisesTheUserCount() throws Exception {
        long before = number(send("GET", "/api/admin/users", null, parentToken, ADMIN_KEY).body(),
                "total");

        register("counted", "PARENT");

        HttpResponse<String> after = send("GET", "/api/admin/users", null, parentToken, ADMIN_KEY);
        assertEquals(200, after.statusCode(), after.body());
        assertEquals(before + 1, number(after.body(), "total"), after.body());
        // A signup today is a signup this week and this month too.
        assertTrue(number(after.body(), "newToday") >= 1, after.body());
        assertTrue(number(after.body(), "newLast7Days") >= number(after.body(), "newToday"),
                after.body());
        assertTrue(number(after.body(), "parents") >= 1, after.body());
    }

    /** Nobody is subscribed in the suite, so the paid tile must read zero, not "all". */
    @Test
    void premiumCountsOnlyLiveSubscriptions() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/admin/users", null, parentToken, ADMIN_KEY);
        assertEquals(0, number(response.body(), "premium"), response.body());
    }

    // --- per application ---

    @Test
    void loggingASignInPutsItsAppOnTheBreakdown() throws Exception {
        String platform = "android-" + UUID.randomUUID().toString().substring(0, 6);
        HttpResponse<String> logged = send("POST", "/api/analytics/login", """
                {"deviceId":"pixel-8","platform":"%s","appVersion":"1.2.3"}
                """.formatted(platform), parentToken, null);
        assertEquals(202, logged.statusCode(), logged.body());

        HttpResponse<String> apps =
                send("GET", "/api/admin/applications", null, parentToken, ADMIN_KEY);
        assertEquals(200, apps.statusCode(), apps.body());
        assertTrue(apps.body().contains("\"platform\":\"" + platform + "\""), apps.body());
        assertTrue(apps.body().contains("\"lastSeenAt\":"), apps.body());

        // The account that just signed in is active today, by this window's definition.
        HttpResponse<String> users = send("GET", "/api/admin/users", null, parentToken, ADMIN_KEY);
        assertTrue(number(users.body(), "activeToday") >= 1, users.body());
        assertTrue(number(users.body(), "activeLast30Days") >= number(users.body(), "activeToday"),
                users.body());
    }

    /** A client that declares no platform is still an app someone is using. */
    @Test
    void signInsWithoutAPlatformAreLabelledUnknown() throws Exception {
        HttpResponse<String> logged = send("POST", "/api/analytics/login",
                "{\"deviceId\":\"mystery-box\"}", parentToken, null);
        assertEquals(202, logged.statusCode(), logged.body());

        HttpResponse<String> apps =
                send("GET", "/api/admin/applications", null, parentToken, ADMIN_KEY);
        assertTrue(apps.body().contains("\"platform\":\"unknown\""), apps.body());
    }

    // --- catalog ---

    @Test
    void catalogCountsEachKindSeparately() throws Exception {
        HttpResponse<String> before =
                send("GET", "/api/admin/catalog", null, parentToken, ADMIN_KEY);
        assertEquals(200, before.statusCode(), before.body());
        long filmsBefore = number(before.body(), "movies");
        long musicBefore = number(before.body(), "music");

        insert("Dashboard Film", MediaType.FILM, 4_000_000_000L);
        insert("Dashboard Song", MediaType.MUSIC, 6_000_000L);

        HttpResponse<String> after =
                send("GET", "/api/admin/catalog", null, parentToken, ADMIN_KEY);
        assertEquals(filmsBefore + 1, number(after.body(), "movies"), after.body());
        assertEquals(musicBefore + 1, number(after.body(), "music"), after.body());
        // Every category is listed even at zero, so the table keeps its shape.
        assertTrue(after.body().contains("\"type\":\"PHOTO\""), after.body());
        assertTrue(after.body().contains("\"type\":\"ADULT\""), after.body());
        assertTrue(after.body().contains("\"label\":\"Video Songs\""), after.body());
    }

    /** The seeded ceremony catalog should be counted, not stepped over. */
    @Test
    void catalogCountsPosterTemplatesAndComponents() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/admin/catalog", null, parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());

        String posters = response.body().substring(response.body().indexOf("\"posters\""));
        assertTrue(number(posters, "templates") > 0, posters);
        assertTrue(number(posters, "components") > 0, posters);
        assertTrue(posters.contains("\"byCategory\":[{"), posters);
        assertTrue(posters.contains("MARRIAGE"), posters);
    }

    // --- engagement ---

    @Test
    void engagementReportsAnIdleServerHonestly() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/admin/engagement", null, parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(0, number(response.body(), "liveStreams"), response.body());
        assertEquals(0, number(response.body(), "streamingNow"), response.body());
        assertTrue(response.body().contains("\"watchHoursLast7Days\":"), response.body());
    }

    private MediaItem insert(String title, MediaType type, long sizeBytes) {
        return items.save(MediaItem.builder()
                .type(type)
                .filePath("D:/Dashboard/" + UUID.randomUUID() + "/" + title.replace(' ', '.'))
                .fileName(title.replace(' ', '.'))
                .fileSize(sizeBytes)
                .title(title)
                .sortTitle(title.toLowerCase())
                .metadataSource(MetadataSource.NFO)
                .addedAt(Instant.now())
                .build());
    }

    private static long number(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        assertTrue(matcher.find(), "no numeric field '" + field + "' in " + json);
        return Long.parseLong(matcher.group(1));
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
