package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;

/**
 * Guests: people with a code, a name, and no account on this server.
 *
 * <p>The interesting assertions are the negative ones. A guest token is the only
 * credential in this application that is not backed by a user row, so what matters is
 * not that it works but that it works for exactly one title in one party and reaches
 * nothing else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WatchPartyGuestIntegrationTest {

    private static final long FRAME_TIMEOUT_MILLIS = 8_000;

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<WebSocketSession> opened = new ArrayList<>();

    private String hostToken;
    private String hostProfileId;
    private MediaItem film;
    private String code;

    @BeforeEach
    void setUp() throws Exception {
        String[] host = registerWithProfile("guest_host", "Amma");
        hostToken = host[0];
        hostProfileId = host[1];

        film = insertFilm("Guest Film");
        HttpResponse<String> created = send("POST", "/api/parties",
                "{\"mediaItemId\":\"%s\"}".formatted(film.getId()), hostToken, hostProfileId);
        assertEquals(201, created.statusCode(), created.body());
        code = extract(created.body(), "code");
    }

    @AfterEach
    void closeSockets() {
        for (WebSocketSession session : opened) {
            try {
                session.close();
            } catch (Exception ignored) {
                // Already closed by the server; not a failure.
            }
        }
        opened.clear();
    }

    // --- the queue ---

    @Test
    void aKnockGetsAPlaceInTheQueueAndNoCredential() throws Exception {
        HttpResponse<String> knocked = knock("Ravi");
        assertEquals(200, knocked.statusCode(), knocked.body());

        assertEquals("PENDING", extract(knocked.body(), "status"));
        assertNotNull(extract(knocked.body(), "requestId"), knocked.body());
        assertNotNull(extract(knocked.body(), "pollToken"), knocked.body());
        // The whole point of approval: no token until the host says yes.
        assertNull(extract(knocked.body(), "token"), knocked.body());
    }

    @Test
    void pollingBeforeTheHostAnswersStillHandsOutNothing() throws Exception {
        HttpResponse<String> knocked = knock("Ravi");
        HttpResponse<String> polled = poll(extract(knocked.body(), "requestId"),
                extract(knocked.body(), "pollToken"));

        assertEquals(200, polled.statusCode(), polled.body());
        assertEquals("PENDING", extract(polled.body(), "status"));
        assertNull(extract(polled.body(), "token"), polled.body());
    }

    @Test
    void theRequestIdAloneIsNotEnoughToCollectSomeoneElsesToken() throws Exception {
        HttpResponse<String> knocked = knock("Ravi");
        String requestId = extract(knocked.body(), "requestId");
        admit(requestId, true);

        // The host sees the request id in their accept prompt, so it must not be the
        // secret. Only the device that knocked holds the poll token.
        assertEquals(404, poll(requestId, "guessed-token").statusCode());
        assertEquals(200, poll(requestId, extract(knocked.body(), "pollToken")).statusCode());
    }

    @Test
    void onlyTheHostSeesWhoIsWaiting() throws Exception {
        knock("Ravi");

        HttpResponse<String> asHost = send("GET", "/api/parties/" + code + "/state",
                null, hostToken, hostProfileId);
        assertTrue(asHost.body().contains("\"name\":\"Ravi\""), asHost.body());

        String[] friend = registerWithProfile("guest_friend", "Kavi");
        send("POST", "/api/parties/" + code + "/join", null, friend[0], friend[1]);
        HttpResponse<String> asMember = send("GET", "/api/parties/" + code + "/state",
                null, friend[0], friend[1]);
        assertTrue(asMember.body().contains("\"pending\":[]"), asMember.body());
    }

    @Test
    void theHostCanRefuseAndTheAnswerSticks() throws Exception {
        HttpResponse<String> knocked = knock("Nuisance");
        String requestId = extract(knocked.body(), "requestId");

        admit(requestId, false);

        HttpResponse<String> polled = poll(requestId, extract(knocked.body(), "pollToken"));
        assertEquals("DENIED", extract(polled.body(), "status"));
        assertNull(extract(polled.body(), "token"), polled.body());

        // A second answer is a double tap, not a change of mind.
        assertEquals(409, admit(requestId, true).statusCode());
    }

    @Test
    void onlyTheHostMayAdmit() throws Exception {
        String requestId = extract(knock("Ravi").body(), "requestId");

        String[] friend = registerWithProfile("guest_bystander", "Kavi");
        send("POST", "/api/parties/" + code + "/join", null, friend[0], friend[1]);

        HttpResponse<String> refused = send("POST", "/api/parties/" + code + "/admit",
                "{\"requestId\":\"%s\",\"allow\":true}".formatted(requestId),
                friend[0], friend[1]);
        assertEquals(403, refused.statusCode(), refused.body());
    }

    // --- what a guest token can and cannot do ---

    @Test
    void admissionHandsOutATokenThatReachesNothingButTheParty() throws Exception {
        String token = admittedGuestToken("Ravi");

        // Not a profile-scoped endpoint: there is no profile behind this token.
        assertEquals(403, send("GET", "/api/media/continue-watching",
                null, token, null).statusCode());
        // Not the account party API either.
        assertEquals(403, send("GET", "/api/parties/" + code + "/state",
                null, token, null).statusCode());
        // Not somebody else's account.
        assertEquals(403, send("GET", "/api/profiles", null, token, null).statusCode());
    }

    @Test
    void aGuestTokenIsNotAnAccountToken() throws Exception {
        String token = admittedGuestToken("Ravi");

        // /api/auth/me resolves the principal as an AppUser; a guest is not one, so the
        // endpoint must not quietly answer for somebody.
        HttpResponse<String> me = send("GET", "/api/auth/me", null, token, null);
        assertNotEquals(200, me.statusCode(), me.body());
    }

    @Test
    void twoGuestsCanShareOnePartyDespiteHavingNoAccountBetweenThem() throws Exception {
        // Both rows carry a null account id; the unique constraint must not read that
        // as the same person twice.
        assertNotNull(admittedGuestToken("Ravi"));
        assertNotNull(admittedGuestToken("Kavi"));

        HttpResponse<String> state = send("GET", "/api/parties/" + code + "/state",
                null, hostToken, hostProfileId);
        assertTrue(state.body().contains("\"name\":\"Ravi\""), state.body());
        assertTrue(state.body().contains("\"name\":\"Kavi\""), state.body());
        assertEquals(2, countOccurrences(state.body(), "\"role\":\"GUEST\""), state.body());
    }

    @Test
    void aTypedNameIsCleanedBeforeTheHostIsShownIt() throws Exception {
        HttpResponse<String> knocked = knock("  Ravi\\n\\n   the\\tGuest  ");
        assertEquals(200, knocked.statusCode(), knocked.body());

        HttpResponse<String> state = send("GET", "/api/parties/" + code + "/state",
                null, hostToken, hostProfileId);
        assertTrue(state.body().contains("\"name\":\"Ravi the Guest\""), state.body());
    }

    // --- the socket ---

    @Test
    void anAdmittedGuestGetsTheClockButCannotDriveIt() throws Exception {
        String token = admittedGuestToken("Ravi");

        Socket host = connect(hostToken);
        Socket guest = connect(token);
        host.await("tick");
        guest.await("tick");

        host.send("{\"type\":\"play\",\"positionSeconds\":42.0}");
        assertTrue(guest.await("play").contains("\"positionSeconds\":42.0"));

        guest.send("{\"type\":\"pause\",\"positionSeconds\":0.0}");
        assertTrue(guest.await("error").contains("only the host"));
    }

    @Test
    void theHostIsToldSomeoneIsAtTheDoor() throws Exception {
        Socket host = connect(hostToken);
        host.await("tick");

        knock("Ravi");

        String prompt = host.await("pending");
        assertTrue(prompt.contains("\"name\":\"Ravi\""), prompt);
        assertTrue(prompt.contains("\"requestId\""), prompt);
    }

    @Test
    void aGuestTokenStopsWorkingWhenThePartyEnds() throws Exception {
        String token = admittedGuestToken("Ravi");
        Socket guest = connect(token);
        guest.await("tick");

        assertEquals(204, send("DELETE", "/api/parties/" + code,
                null, hostToken, hostProfileId).statusCode());
        assertTrue(guest.await("ended").contains("host ended it"));

        // The token has hours left on it and is now worthless, which is the point:
        // the party, not the expiry, is what authorises a guest.
        assertThrows(ExecutionException.class, () -> connect(token));
    }

    // --- plumbing ---

    private HttpResponse<String> knock(String name) throws Exception {
        return send("POST", "/api/parties/" + code + "/guest",
                "{\"name\":\"%s\"}".formatted(name), null, null);
    }

    private HttpResponse<String> poll(String requestId, String pollToken) throws Exception {
        return send("GET", "/api/parties/" + code + "/guest/" + requestId
                + "?token=" + URLEncoder(pollToken), null, null, null);
    }

    private HttpResponse<String> admit(String requestId, boolean allow) throws Exception {
        return send("POST", "/api/parties/" + code + "/admit",
                "{\"requestId\":\"%s\",\"allow\":%b}".formatted(requestId, allow),
                hostToken, hostProfileId);
    }

    /** Knocks, has the host accept, and returns the guest token that comes back. */
    private String admittedGuestToken(String name) throws Exception {
        HttpResponse<String> knocked = knock(name);
        assertEquals(200, knocked.statusCode(), knocked.body());
        String requestId = extract(knocked.body(), "requestId");

        assertEquals(200, admit(requestId, true).statusCode());

        HttpResponse<String> polled = poll(requestId, extract(knocked.body(), "pollToken"));
        assertEquals("ADMITTED", extract(polled.body(), "status"), polled.body());
        String token = extract(polled.body(), "token");
        assertNotNull(token, polled.body());
        return token;
    }

    /** Poll tokens are URL-safe Base64, but the encoder still owes them a pass. */
    private static String URLEncoder(String raw) {
        return java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final class Socket {

        private final WebSocketSession session;
        private final BlockingQueue<String> frames;

        private Socket(WebSocketSession session, BlockingQueue<String> frames) {
            this.session = session;
            this.frames = frames;
        }

        private void send(String payload) throws Exception {
            session.sendMessage(new TextMessage(payload));
        }

        private String await(String type) throws Exception {
            String needle = "\"type\":\"" + type + "\"";
            long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                String frame = frames.poll(250, TimeUnit.MILLISECONDS);
                if (frame != null && frame.contains(needle)) {
                    return frame;
                }
            }
            throw new AssertionError("no '" + type + "' frame arrived");
        }
    }

    private Socket connect(String token) throws Exception {
        String url = "ws://localhost:" + port + "/ws/party?code=" + code + "&token=" + token;
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();

        WebSocketSession session = new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                        frames.add(message.getPayload());
                    }
                }, url)
                .get(10, TimeUnit.SECONDS);

        opened.add(session);
        return new Socket(session, frames);
    }

    private HttpResponse<String> send(String method, String path, String json,
                                      String bearer, String profile) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (profile != null) {
            builder.header("X-Profile-Id", profile);
        }
        builder.method(method, json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String[] registerWithProfile(String prefix, String profileName) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"%s_%s","email":"%s_%s@example.com",
                 "password":"pw123456","displayName":"Guest Tester"}
                """.formatted(prefix, unique, prefix, unique), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        String bearer = extract(registered.body(), "token");

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"%s\",\"ageMode\":\"OLDER\"}".formatted(profileName), bearer, null);
        assertEquals(201, profile.statusCode(), profile.body());
        return new String[]{bearer, extract(profile.body(), "id")};
    }

    private MediaItem insertFilm(String title) {
        String slug = title.replace(' ', '.') + "." + UUID.randomUUID().toString().substring(0, 8);
        return items.save(MediaItem.builder()
                .type(MediaType.FILM)
                .libraryName("Films")
                .filePath("D:/Media/" + slug + ".mkv")
                .fileName(slug + ".mkv")
                .folderPath("D:/Media")
                .fileSize(1_400_000_000L)
                .title(title)
                .sortTitle(title.toLowerCase())
                .year(2015)
                .runtimeMinutes(120)
                .build());
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
