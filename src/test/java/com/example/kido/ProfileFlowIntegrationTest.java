package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/**
 * End-to-end drive of the Profiles + per-profile Progress API over real HTTP,
 * backed by in-memory H2 (real JPA). Covers CRUD, progress replace, offline
 * merge, ownership isolation and auth gating.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfileFlowIntegrationTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> send(String method, String path, String json, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path));
        if (json != null) b.header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        HttpRequest.BodyPublisher body = json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json);
        b.method(method, body);
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

    /** Register a fresh parent and return the JWT. */
    private String registerParent(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"secret1\",\"displayName\":\"" + username + "\"}";
        HttpResponse<String> res = send("POST", "/api/auth/register", body, null);
        assertEquals(201, res.statusCode());
        return str(res.body(), "token");
    }

    @Test
    void full_profile_and_progress_flow() throws Exception {
        String token = registerParent("parent_a");

        // Create a child
        HttpResponse<String> create = send("POST", "/api/profiles",
                "{\"name\":\"Aarav\",\"ageMode\":\"YOUNG\",\"avatarTint\":\"#FFE9C2\"}", token);
        assertEquals(201, create.statusCode());
        String pid = str(create.body(), "id");
        assertNotNull(pid);
        assertEquals("Aarav", str(create.body(), "name"));
        assertEquals("YOUNG", str(create.body(), "ageMode"));

        // List shows it
        HttpResponse<String> list = send("GET", "/api/profiles", null, token);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("Aarav"));

        // Progress starts empty
        HttpResponse<String> p0 = send("GET", "/api/profiles/" + pid + "/progress", null, token);
        assertEquals(200, p0.statusCode());
        assertEquals(0, num(p0.body(), "stars"));

        // Replace progress
        HttpResponse<String> put = send("PUT", "/api/profiles/" + pid + "/progress",
                "{\"stars\":10,\"streak\":6,\"chessLessonStep\":3,\"puzzlesSolved\":2,\"chessWins\":1,\"memoryBest\":12,"
                        + "\"badges\":[\"knight\"],\"pals\":[\"kiku\"],\"worlds\":{\"chess\":38}}", token);
        assertEquals(200, put.statusCode());
        assertEquals(10, num(put.body(), "stars"));
        assertEquals(6, num(put.body(), "streak"));

        // Offline merge: keep the best of each
        HttpResponse<String> sync = send("POST", "/api/profiles/" + pid + "/progress/sync",
                "{\"stars\":5,\"streak\":8,\"chessLessonStep\":2,\"puzzlesSolved\":9,\"chessWins\":0,\"memoryBest\":20,"
                        + "\"badges\":[\"puzzle\"],\"pals\":[],\"worlds\":{\"chess\":20,\"numbers\":55}}", token);
        assertEquals(200, sync.statusCode());
        assertEquals(10, num(sync.body(), "stars"));        // max(10,5)
        assertEquals(8, num(sync.body(), "streak"));        // max(6,8)
        assertEquals(9, num(sync.body(), "puzzlesSolved")); // max(2,9)
        assertEquals(12, num(sync.body(), "memoryBest"));   // min non-zero(12,20)
        assertTrue(sync.body().contains("knight") && sync.body().contains("puzzle")); // union
        assertTrue(sync.body().contains("\"chess\":38"));   // max(38,20)
        assertTrue(sync.body().contains("\"numbers\":55"));
    }

    @Test
    void partial_sync_payload_keeps_omitted_fields() throws Exception {
        String token = registerParent("parent_p");
        String pid = str(send("POST", "/api/profiles", "{\"name\":\"Kid\"}", token).body(), "id");

        send("PUT", "/api/profiles/" + pid + "/progress",
                "{\"stars\":10,\"streak\":6,\"chessLessonStep\":3,\"chessWins\":1,\"memoryBest\":12}", token);

        // Sync omits chessLessonStep and chessWins entirely — must not 500.
        HttpResponse<String> sync = send("POST", "/api/profiles/" + pid + "/progress/sync",
                "{\"stars\":5,\"streak\":8}", token);
        assertEquals(200, sync.statusCode());
        assertEquals(10, num(sync.body(), "stars"));           // max(10,5)
        assertEquals(8, num(sync.body(), "streak"));           // max(6,8)
        assertEquals(3, num(sync.body(), "chessLessonStep"));  // kept (max(3,0))
        assertEquals(1, num(sync.body(), "chessWins"));        // kept (max(1,0))
        assertEquals(12, num(sync.body(), "memoryBest"));      // kept
    }

    @Test
    void profiles_are_isolated_per_owner() throws Exception {
        String tokenA = registerParent("owner_a");
        String tokenB = registerParent("owner_b");

        HttpResponse<String> create = send("POST", "/api/profiles",
                "{\"name\":\"Meera\",\"ageMode\":\"OLDER\"}", tokenA);
        String pid = str(create.body(), "id");

        // Owner B cannot see or read owner A's profile
        assertEquals(404, send("GET", "/api/profiles/" + pid + "/progress", null, tokenB).statusCode());
        assertEquals(0, send("GET", "/api/profiles", null, tokenB).body().split("\"id\"").length - 1);

        // No token at all is rejected
        assertEquals(403, send("GET", "/api/profiles", null, null).statusCode());
    }
}
