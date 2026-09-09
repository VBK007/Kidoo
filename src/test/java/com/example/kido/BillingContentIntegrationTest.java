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

/** Subscription/entitlements, ads gating and the content CMS, over real HTTP on H2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BillingContentIntegrationTest {

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
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(send("POST", "/api/auth/register", body, null).body());
        return m.find() ? m.group(1) : null;
    }

    @Test
    void subscription_upgrade_flips_entitlements_and_ads() throws Exception {
        String t = token("plus_parent");

        // Plans are public
        assertTrue(send("GET", "/api/plans", null, null).body().contains("kiduu_plus_monthly"));

        // Free by default: not premium, ads on
        assertTrue(!bool(send("GET", "/api/entitlements", null, t).body(), "isPremium"));
        assertTrue(bool(send("GET", "/api/ads/config", null, t).body(), "adsEnabled"));

        // Verify a purchase → premium
        HttpResponse<String> ver = send("POST", "/api/subscription/verify",
                "{\"platform\":\"android\",\"productId\":\"kiduu_plus_monthly\",\"purchaseToken\":\"tok123\"}", t);
        assertEquals(200, ver.statusCode());
        assertTrue(bool(ver.body(), "premium"));

        // Now premium: entitled, ads off
        assertTrue(bool(send("GET", "/api/entitlements", null, t).body(), "isPremium"));
        assertTrue(!bool(send("GET", "/api/ads/config", null, t).body(), "adsEnabled"));

        // Unknown product rejected
        assertEquals(400, send("POST", "/api/subscription/verify",
                "{\"productId\":\"bogus\"}", t).statusCode());

        // A blank purchase token fails verification
        assertEquals(400, send("POST", "/api/subscription/verify",
                "{\"platform\":\"android\",\"productId\":\"kiduu_plus_monthly\",\"purchaseToken\":\"\"}", t).statusCode());
    }

    @Test
    void content_publish_manifest_and_fetch() throws Exception {
        String t = token("cms_parent");

        // Empty manifest (public, no token)
        assertEquals(0, num(send("GET", "/api/content/manifest", null, null).body(), "latestVersion"));

        // Publishing without the admin key is forbidden
        assertEquals(403, send("POST", "/api/admin/content",
                "{\"type\":\"daily_challenge\",\"key\":\"today\",\"body\":\"v1\"}", t).statusCode());

        // Publish two items (with admin key)
        assertEquals(1, num(publish("{\"type\":\"daily_challenge\",\"key\":\"today\",\"body\":\"v1\"}", t).body(), "version"));
        assertEquals(2, num(publish("{\"type\":\"chess_puzzle\",\"key\":\"p4\",\"body\":\"mate\"}", t).body(), "version"));

        // Manifest since 0 sees both
        String man0 = send("GET", "/api/content/manifest?since=0", null, null).body();
        assertEquals(2, num(man0, "latestVersion"));
        assertTrue(man0.contains("daily_challenge") && man0.contains("chess_puzzle"));

        // Delta since 1 sees only the newer one
        String man1 = send("GET", "/api/content/manifest?since=1", null, null).body();
        assertTrue(man1.contains("chess_puzzle") && !man1.contains("daily_challenge"));

        // Fetch by type (public)
        assertTrue(send("GET", "/api/content/daily_challenge", null, null).body().contains("v1"));

        // Re-publishing the same key upserts and bumps the version
        assertEquals(3, num(publish("{\"type\":\"daily_challenge\",\"key\":\"today\",\"body\":\"v2\"}", t).body(), "version"));
        assertTrue(send("GET", "/api/content/daily_challenge", null, null).body().contains("v2"));
    }

    /** POST /api/admin/content with the admin key header. */
    private HttpResponse<String> publish(String json, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/content"))
                .header("Content-Type", "application/json")
                .header("X-Admin-Key", "test-admin-key");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.POST(HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
