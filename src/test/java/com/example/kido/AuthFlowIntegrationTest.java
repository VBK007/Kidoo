package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.kido.auth.RefreshToken;
import com.example.kido.auth.RefreshTokenRepository;
import com.example.kido.user.AppUser;
import com.example.kido.user.UserRepository;

/**
 * End-to-end drive of the REST + security + JWT stack over real HTTP against an
 * embedded server, backed by a real JPA/Hibernate persistence layer running on
 * an in-memory H2 database (PostgreSQL mode). Verifies JSON (de)serialization,
 * bean validation, the Spring Security filter chain (public vs protected),
 * BCrypt hashing, JWT round-trip, and actual DB reads/writes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    // --- helpers ----------------------------------------------------------

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> post(String path, String json, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
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

    private String registerTimmy() throws Exception {
        String body = "{\"username\":\"Timmy\",\"email\":\"timmy@example.com\","
                + "\"password\":\"secret1\",\"displayName\":\"Timmy\"}";
        HttpResponse<String> res = post("/api/auth/register", body, null);
        assertEquals(201, res.statusCode());
        return str(res.body(), "token");
    }

    // --- tests ------------------------------------------------------------

    @Test
    void health_is_public() throws Exception {
        HttpResponse<String> res = get("/api/health", null);
        assertEquals(200, res.statusCode());
        assertEquals("UP", str(res.body(), "status"));
    }

    @Test
    void register_persists_user_and_hashes_password() throws Exception {
        String token = registerTimmy();
        assertTrue(token != null && !token.isBlank());

        // The user is really in the database, with a hashed password.
        AppUser saved = userRepository.findByUsername("timmy").orElseThrow();
        assertNotNull(saved.getId());
        assertEquals("CHILD", saved.getRole().name());
        assertTrue(!saved.getPasswordHash().contains("secret1"));
        assertTrue(passwordEncoder.matches("secret1", saved.getPasswordHash()));
    }

    @Test
    void register_duplicate_username_conflicts() throws Exception {
        registerTimmy();
        HttpResponse<String> dup = post("/api/auth/register",
                "{\"username\":\"Timmy\",\"email\":\"other@example.com\","
                        + "\"password\":\"secret1\",\"displayName\":\"Timmy\"}", null);
        assertEquals(409, dup.statusCode());
    }

    @Test
    void register_rejects_invalid_email() throws Exception {
        String body = "{\"username\":\"bad\",\"email\":\"not-an-email\","
                + "\"password\":\"secret1\",\"displayName\":\"Bad\"}";
        assertEquals(400, post("/api/auth/register", body, null).statusCode());
    }

    @Test
    void protected_endpoint_requires_auth() throws Exception {
        assertEquals(403, get("/api/progress", null).statusCode());
    }

    @Test
    void full_flow_register_login_me_sync() throws Exception {
        registerTimmy();

        // Login → token
        HttpResponse<String> login = post("/api/auth/login",
                "{\"usernameOrEmail\":\"timmy\",\"password\":\"secret1\"}", null);
        assertEquals(200, login.statusCode());
        String token = str(login.body(), "token");
        assertTrue(token != null && !token.isBlank());

        // Protected /me
        HttpResponse<String> me = get("/api/auth/me", token);
        assertEquals(200, me.statusCode());
        assertEquals("timmy", str(me.body(), "username"));
        assertEquals(0, num(me.body(), "stars"));

        // Progress sync merges (new user 0 vs client 9 → 9) and persists
        HttpResponse<String> sync = post("/api/progress/sync",
                "{\"stars\":9,\"chessWins\":5,\"memoryBest\":12}", token);
        assertEquals(200, sync.statusCode());
        assertEquals(9, num(sync.body(), "stars"));
        assertEquals(5, num(sync.body(), "chessWins"));
        assertEquals(12, num(sync.body(), "memoryBest"));

        // The merge really persisted to the DB
        assertEquals(9, userRepository.findByUsername("timmy").orElseThrow().getProgress().getStars());

        // Garbage token rejected
        assertEquals(403, get("/api/auth/me", "not.a.jwt").statusCode());
    }

    @Test
    void wrong_password_is_unauthorized() throws Exception {
        registerTimmy();
        HttpResponse<String> res = post("/api/auth/login",
                "{\"usernameOrEmail\":\"timmy\",\"password\":\"WRONG\"}", null);
        assertEquals(401, res.statusCode());
    }

    // --- refresh tokens ---------------------------------------------------

    /** Signs in and returns {access, refresh}. */
    private String[] signIn() throws Exception {
        registerTimmy();
        HttpResponse<String> login = post("/api/auth/login",
                "{\"usernameOrEmail\":\"timmy\",\"password\":\"secret1\"}", null);
        assertEquals(200, login.statusCode());
        return new String[] {str(login.body(), "token"), str(login.body(), "refreshToken")};
    }

    @Test
    void sign_in_hands_out_a_refresh_token_and_stores_only_its_hash() throws Exception {
        String[] tokens = signIn();
        assertTrue(tokens[1] != null && !tokens[1].isBlank());

        // Two of them: one from register, one from login. Neither is stored in the clear.
        assertEquals(2, refreshTokenRepository.count());
        refreshTokenRepository.findAll().forEach(row -> {
            assertNotEquals(tokens[1], row.getTokenHash());
            assertNotNull(row.getExpiresAt());
            assertTrue(!row.isRevoked());
        });
    }

    @Test
    void refresh_returns_a_working_access_token_and_rotates_the_refresh_token() throws Exception {
        String[] tokens = signIn();

        HttpResponse<String> res = post("/api/auth/refresh",
                "{\"refreshToken\":\"" + tokens[1] + "\"}", null);
        assertEquals(200, res.statusCode());

        String access = str(res.body(), "token");
        String rotated = str(res.body(), "refreshToken");
        assertTrue(access != null && !access.isBlank());
        assertNotEquals(tokens[1], rotated);
        assertEquals("timmy", str(res.body(), "username"));

        // The new access token really authenticates.
        assertEquals(200, get("/api/auth/me", access).statusCode());

        // And the replacement refreshes in its turn.
        assertEquals(200, post("/api/auth/refresh",
                "{\"refreshToken\":\"" + rotated + "\"}", null).statusCode());
    }

    @Test
    void a_spent_refresh_token_does_not_work_twice() throws Exception {
        String[] tokens = signIn();
        assertEquals(200, post("/api/auth/refresh",
                "{\"refreshToken\":\"" + tokens[1] + "\"}", null).statusCode());

        HttpResponse<String> replay = post("/api/auth/refresh",
                "{\"refreshToken\":\"" + tokens[1] + "\"}", null);
        assertEquals(401, replay.statusCode());
    }

    @Test
    void refresh_rejects_an_unknown_token_and_an_empty_one() throws Exception {
        assertEquals(401, post("/api/auth/refresh",
                "{\"refreshToken\":\"not-a-real-token\"}", null).statusCode());
        assertEquals(400, post("/api/auth/refresh", "{\"refreshToken\":\"\"}", null).statusCode());
    }

    /**
     * A replay from outside the race window is read as a second holder, and takes the
     * account's live sessions down with it — including the one that legitimately
     * rotated. Backdating the revocation is what stands in for waiting out the grace
     * period.
     */
    @Test
    void a_replayed_refresh_token_ends_every_session_on_the_account() throws Exception {
        String[] tokens = signIn();
        HttpResponse<String> first = post("/api/auth/refresh",
                "{\"refreshToken\":\"" + tokens[1] + "\"}", null);
        assertEquals(200, first.statusCode());
        String rotated = str(first.body(), "refreshToken");

        for (RefreshToken row : refreshTokenRepository.findAll()) {
            if (row.isRevoked()) {
                row.setRevokedAt(Instant.now().minusSeconds(300));
                refreshTokenRepository.save(row);
            }
        }

        assertEquals(401, post("/api/auth/refresh",
                "{\"refreshToken\":\"" + tokens[1] + "\"}", null).statusCode());

        // The honest device's token is collateral: it can no longer refresh either.
        assertEquals(401, post("/api/auth/refresh",
                "{\"refreshToken\":\"" + rotated + "\"}", null).statusCode());
        assertTrue(refreshTokenRepository.findAll().stream().allMatch(RefreshToken::isRevoked));
    }
}
