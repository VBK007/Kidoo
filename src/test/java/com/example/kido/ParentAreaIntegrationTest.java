package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Activity/Dashboard + Settings + Screen-time + Parent-PIN, over real HTTP on H2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ParentAreaIntegrationTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String json, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (json != null) b.header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String str(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private int num(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private boolean bool(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)").matcher(body);
        return m.find() && m.group(1).equals("true");
    }

    private String token(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@x.com\",\"password\":\"secret1\",\"displayName\":\"" + username + "\"}";
        return str(send("POST", "/api/auth/register", body, null).body(), "token");
    }

    private String profile(String token) throws Exception {
        return str(send("POST", "/api/profiles", "{\"name\":\"Aarav\",\"ageMode\":\"YOUNG\"}", token).body(), "id");
    }

    @Test
    void activity_feeds_the_dashboard() throws Exception {
        String t = token("dash_parent");
        String pid = profile(t);

        assertEquals(202, send("POST", "/api/profiles/" + pid + "/activity", "{\"world\":\"chess\",\"seconds\":600}", t).statusCode());
        assertEquals(202, send("POST", "/api/profiles/" + pid + "/activity", "{\"world\":\"numbers\",\"seconds\":300}", t).statusCode());

        HttpResponse<String> dash = send("GET", "/api/profiles/" + pid + "/dashboard?range=week", null, t);
        assertEquals(200, dash.statusCode());
        assertEquals(900, num(dash.body(), "learningTimeSeconds"));
        assertTrue(dash.body().contains("chess") && dash.body().contains("numbers"));
    }

    @Test
    void profile_and_account_settings_and_screen_time() throws Exception {
        String t = token("set_parent");
        String pid = profile(t);

        // Profile settings
        HttpResponse<String> ps = send("PUT", "/api/profiles/" + pid + "/settings",
                "{\"readingLevel\":\"OLDER\",\"narration\":false,\"chessHints\":false}", t);
        assertEquals(200, ps.statusCode());
        assertEquals("OLDER", str(ps.body(), "readingLevel"));
        assertTrue(!bool(ps.body(), "narration"));

        // Account settings default then update
        assertEquals(30, num(send("GET", "/api/account/settings", null, t).body(), "dailyScreenTimeMin"));
        HttpResponse<String> as = send("PUT", "/api/account/settings", "{\"language\":\"hi\",\"dailyScreenTimeMin\":45}", t);
        assertEquals("hi", str(as.body(), "language"));
        assertEquals(45, num(as.body(), "dailyScreenTimeMin"));

        // Screen time reflects the new limit and logged activity
        send("POST", "/api/profiles/" + pid + "/activity", "{\"world\":\"chess\",\"seconds\":600}", t);
        HttpResponse<String> stime = send("GET", "/api/profiles/" + pid + "/screen-time", null, t);
        assertEquals(45, num(stime.body(), "limitMin"));
        assertEquals(600, num(stime.body(), "usedSecondsToday"));
        assertTrue(!bool(stime.body(), "limitReached")); // 600s < 45min
    }

    @Test
    void parent_pin_set_and_verify() throws Exception {
        String t = token("pin_parent");

        assertTrue(!bool(send("GET", "/api/account/parent-pin", null, t).body(), "isSet"));
        assertEquals(204, send("POST", "/api/account/parent-pin", "{\"pin\":\"1234\"}", t).statusCode());
        assertTrue(bool(send("GET", "/api/account/parent-pin", null, t).body(), "isSet"));

        assertTrue(bool(send("POST", "/api/account/parent-pin/verify", "{\"pin\":\"1234\"}", t).body(), "valid"));
        assertTrue(!bool(send("POST", "/api/account/parent-pin/verify", "{\"pin\":\"0000\"}", t).body(), "valid"));
        assertEquals(400, send("POST", "/api/account/parent-pin", "{\"pin\":\"12\"}", t).statusCode()); // not 4 digits
    }
}
