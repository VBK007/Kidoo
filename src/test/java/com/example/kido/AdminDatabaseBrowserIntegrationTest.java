package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Covers the database browser — the widest read surface on the server.
 *
 * <p>Most of these are about what it refuses. It can see every table, so the tests
 * that matter are the ones proving it will not hand over a password hash, will not
 * put a page of email addresses on screen, and will not take a column name it was
 * not offered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminDatabaseBrowserIntegrationTest {

    private static final String ADMIN_KEY = "test-admin-key";

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private String parentToken;
    private String childToken;
    private String ownEmail;

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

    private HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, parentToken, ADMIN_KEY);
    }

    private String register(String prefix, String role) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "_" + unique + "@example.com";
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"%s_%s","email":"%s","password":"pw123456",
                 "displayName":"Browser Tester","role":"%s"}
                """.formatted(prefix, unique, email, role), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        if ("PARENT".equals(role)) {
            ownEmail = email;
        }
        return extract(registered.body(), "token");
    }

    @BeforeEach
    void setUp() throws Exception {
        parentToken = register("dbowner", "PARENT");
        childToken = register("dbkid", "CHILD");
        assertNotNull(parentToken);
    }

    // --- gating ---

    @Test
    void browserIsOwnerOnly() throws Exception {
        assertEquals(403, send("GET", "/api/admin/db/tables", null, parentToken, null).statusCode());
        assertEquals(403, send("GET", "/api/admin/db/tables", null, childToken, ADMIN_KEY).statusCode());
        assertEquals(403, send("GET", "/api/admin/db/tables", null, null, ADMIN_KEY).statusCode());
        assertEquals(403,
                send("GET", "/api/admin/db/tables/users/rows", null, childToken, ADMIN_KEY).statusCode());
    }

    // --- catalog ---

    @Test
    void listsEveryTableWithItsRowCount() throws Exception {
        HttpResponse<String> response = get("/api/admin/db/tables");
        assertEquals(200, response.statusCode(), response.body());

        String body = response.body().toLowerCase(Locale.ROOT);
        assertTrue(body.contains("\"name\":\"users\""), body);
        assertTrue(body.contains("\"name\":\"poster_templates\""), body);
        assertTrue(body.contains("\"name\":\"login_events\""), body);
        assertTrue(body.contains("\"rows\":"), body);
        assertTrue(body.contains("\"primarykey\":[\"id\"]"), body);
    }

    @Test
    void columnsSayHowEachOneWillBeTreated() throws Exception {
        String body = get("/api/admin/db/tables/users/columns").body().toLowerCase(Locale.ROOT);

        assertTrue(body.contains("\"name\":\"password_hash\",\"type\":")
                && body.contains("\"handling\":\"secret\""), body);
        assertTrue(body.contains("\"name\":\"email\""), body);
        assertTrue(body.contains("\"handling\":\"personal\""), body);
        assertTrue(body.contains("\"primarykey\":true"), body);
    }

    /**
     * A name match on a column that cannot hold an address is a false positive.
     * {@code weekly_email_summary} is a boolean preference; masking it to "t***"
     * would hide a harmless setting and protect nobody.
     */
    @Test
    void doesNotMaskABooleanJustBecauseItIsCalledEmailSomething() throws Exception {
        String columns = get("/api/admin/db/tables/users/columns").body().toLowerCase(Locale.ROOT);
        int at = columns.indexOf("\"name\":\"weekly_email_summary\"");
        assertTrue(at >= 0, columns);
        assertTrue(columns.substring(at, at + 160).contains("\"handling\":\"plain\""),
                columns.substring(at, at + 160));
    }

    // --- what it will not show ---

    /** The whole point of the policy: a hash is never selected, in any mode. */
    @Test
    void neverReturnsAPasswordHash() throws Exception {
        String page = get("/api/admin/db/tables/users/rows?size=200").body();
        assertFalse(page.toLowerCase(Locale.ROOT).contains("$2a$"), "bcrypt hash in a listing");
        assertFalse(page.toLowerCase(Locale.ROOT).contains("password_hash\":\""), page);

        String id = firstUserId();
        String row = get("/api/admin/db/tables/users/rows/" + id).body();
        assertFalse(row.toLowerCase(Locale.ROOT).contains("$2a$"), "bcrypt hash in a revealed row");
    }

    @Test
    void masksEmailAddressesInAListing() throws Exception {
        String page = get("/api/admin/db/tables/users/rows?size=200").body();
        assertFalse(page.contains(ownEmail), "a listing handed over a whole email address");
        assertTrue(page.contains("***@example.com"), page);
        assertTrue(page.contains("\"masked\":true"), page);
    }

    /** Opening one row is the deliberate act that unmasks it — and only it. */
    @Test
    void revealsOneRowWhenItIsAskedForByKey() throws Exception {
        String id = firstUserId();
        HttpResponse<String> response = get("/api/admin/db/tables/users/rows/" + id);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"masked\":false"), response.body());
        assertTrue(response.body().contains("@example.com"), response.body());
        assertFalse(response.body().contains("***@"), response.body());
    }

    /** A layout is a document; a page of forty of them is not a table. */
    @Test
    void describesLargeColumnsRatherThanShippingThem() throws Exception {
        String columns = get("/api/admin/db/tables/poster_templates/columns")
                .body().toLowerCase(Locale.ROOT);
        assertTrue(columns.contains("\"name\":\"layout\""), columns);
        assertTrue(columns.contains("\"handling\":\"large\""), columns);

        String page = get("/api/admin/db/tables/poster_templates/rows?size=5").body();
        assertTrue(page.contains("\"layout\":null"), page);
    }

    // --- reading rows ---

    @Test
    void pagesAndSortsOnAColumnThatExists() throws Exception {
        HttpResponse<String> first = get("/api/admin/db/tables/poster_templates/rows?size=5&sort=name");
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(5, number(first.body(), "size"));
        assertEquals(0, number(first.body(), "page"));
        assertTrue(number(first.body(), "total") > 5, first.body());
        assertTrue(number(first.body(), "totalPages") > 1, first.body());

        HttpResponse<String> second =
                get("/api/admin/db/tables/poster_templates/rows?size=5&page=1&sort=name");
        assertEquals(1, number(second.body(), "page"));
        assertFalse(first.body().equals(second.body()), "page 2 returned page 1");
    }

    @Test
    void searchesTheTextColumns() throws Exception {
        String username = usernameOfFirstUser();
        HttpResponse<String> response = get("/api/admin/db/tables/users/rows?q="
                + URLEncoder.encode(username, StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, number(response.body(), "total"), response.body());
        assertTrue(response.body().contains(username), response.body());
    }

    // --- filtering ---

    @Test
    void filtersOnOneColumn() throws Exception {
        String username = usernameOfFirstUser();
        HttpResponse<String> response = get("/api/admin/db/tables/users/rows?filter=username&filterOp=eq"
                + "&filterValue=" + URLEncoder.encode(username, StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, number(response.body(), "total"), response.body());
        assertTrue(response.body().contains(username), response.body());
    }

    /** The setUp registers one PARENT and one CHILD, so both are there to tell apart. */
    @Test
    void filtersOnAColumnThatIsNotText() throws Exception {
        long parents = number(get("/api/admin/db/tables/users/rows?filter=role&filterOp=eq"
                + "&filterValue=PARENT").body(), "total");
        long notParents = number(get("/api/admin/db/tables/users/rows?filter=role&filterOp=ne"
                + "&filterValue=PARENT").body(), "total");
        long all = number(get("/api/admin/db/tables/users/rows").body(), "total");

        assertTrue(parents >= 1, "the owner this test signed in as is a PARENT");
        assertTrue(notParents >= 1, "setUp also registers a CHILD");
        // The halves account for every row: `ne` keeps the nulls precisely so that
        // filtering one way and then the other cannot lose rows between them.
        assertEquals(all, parents + notParents, "eq and ne should partition the table");
    }

    @Test
    void filterAndSearchNarrowTogether() throws Exception {
        String username = usernameOfFirstUser();
        // A search that matches this row, and a filter that cannot: no rows, rather
        // than the search's answer or the filter's.
        HttpResponse<String> response = get("/api/admin/db/tables/users/rows"
                + "?q=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&filter=username&filterOp=eq&filterValue=" + UUID.randomUUID());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(0, number(response.body(), "total"), response.body());
    }

    /** An empty box is somebody mid-thought, not a filter that matches everything. */
    @Test
    void anEmptyFilterValueIsNoFilter() throws Exception {
        long all = number(get("/api/admin/db/tables/users/rows").body(), "total");
        assertEquals(all,
                number(get("/api/admin/db/tables/users/rows?filter=username&filterValue=").body(), "total"));
    }

    /**
     * The injection guard again, on the filter this time: the column is matched
     * against the catalogue and the value is bound, so neither half of a filter can
     * carry SQL into a statement.
     */
    @Test
    void refusesAFilterColumnItDoesNotHave() throws Exception {
        HttpResponse<String> response =
                get("/api/admin/db/tables/users/rows?filter=id%3B%20drop%20table%20users&filterValue=x");
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("is not a column"), response.body());
        assertEquals(200, get("/api/admin/db/tables/users/rows").statusCode());
    }

    @Test
    void aFilterValueIsBoundRatherThanConcatenated() throws Exception {
        HttpResponse<String> response = get("/api/admin/db/tables/users/rows?filter=username&filterOp=eq"
                + "&filterValue=" + URLEncoder.encode("' or '1'='1", StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(0, number(response.body(), "total"),
                "a bound parameter matches the literal string, so nothing matches");
        assertEquals(200, get("/api/admin/db/tables/users/rows").statusCode());
    }

    /**
     * Filtering on a hash would answer "does any row have this value?" for anything
     * asked — an oracle for the one thing the column exists to keep. Not selecting
     * it and not comparing it are two halves of the same guarantee.
     */
    @Test
    void refusesToFilterOnASecretColumn() throws Exception {
        HttpResponse<String> response =
                get("/api/admin/db/tables/users/rows?filter=password_hash&filterOp=eq&filterValue=x");
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("cannot be filtered on"), response.body());
    }

    @Test
    void refusesAComparisonItDoesNotKnow() throws Exception {
        assertEquals(400, get("/api/admin/db/tables/users/rows"
                + "?filter=username&filterOp=regex&filterValue=x").statusCode());
    }

    /** A page size is a cap; nonsense falls back to the default rather than to one row. */
    @Test
    void clampsAnAbsurdPageSize() throws Exception {
        assertEquals(200, number(get("/api/admin/db/tables/users/rows?size=100000").body(), "size"));
        assertEquals(25, number(get("/api/admin/db/tables/users/rows?size=-4").body(), "size"));
    }

    // --- what it refuses ---

    @Test
    void refusesATableThatIsNotInTheSchema() throws Exception {
        HttpResponse<String> response = get("/api/admin/db/tables/pg_shadow/rows");
        assertEquals(404, response.statusCode(), response.body());
        assertTrue(response.body().contains("No table named"), response.body());
    }

    /**
     * The injection guard, from the outside: a sort parameter is matched against
     * the catalogue, so anything that is not a real column never reaches a
     * statement — it comes back as a refusal naming the table.
     */
    @Test
    void refusesASortColumnItDoesNotHave() throws Exception {
        HttpResponse<String> response =
                get("/api/admin/db/tables/users/rows?sort=id%3B%20drop%20table%20users");
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("cannot be sorted on")
                || response.body().contains("is not a column"), response.body());

        // And the table is still there, which is the claim that matters.
        assertEquals(200, get("/api/admin/db/tables/users/rows").statusCode());
    }

    /** Sorting on a hash would order accounts by a secret. It is not offered. */
    @Test
    void refusesToSortOnASecretColumn() throws Exception {
        assertEquals(400, get("/api/admin/db/tables/users/rows?sort=password_hash").statusCode());
    }

    @Test
    void answersNotFoundForAKeyThatIsNotThere() throws Exception {
        assertEquals(404, get("/api/admin/db/tables/users/rows/no-such-id").statusCode());
    }

    private String firstUserId() throws Exception {
        String body = get("/api/admin/db/tables/users/rows?size=1").body();
        String id = extract(body.substring(body.indexOf("\"rows\"")), "id");
        assertNotNull(id, body);
        return id;
    }

    private String usernameOfFirstUser() throws Exception {
        String body = get("/api/admin/db/tables/users/rows?size=1").body();
        String username = extract(body.substring(body.indexOf("\"rows\"")), "username");
        assertNotNull(username, body);
        return username;
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
