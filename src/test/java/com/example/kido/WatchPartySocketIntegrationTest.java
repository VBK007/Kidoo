package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * The shared playhead over a real socket, between two accounts.
 *
 * <p>Runs against an actual WebSocket connection rather than calling the registry
 * directly, because most of what can go wrong here is in the wiring: the handshake
 * refusing or admitting the wrong people, the security chain swallowing the upgrade,
 * frames that do not round-trip through Jackson.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WatchPartySocketIntegrationTest {

    /** Frames arrive on a container thread, so every wait is bounded. */
    private static final long FRAME_TIMEOUT_MILLIS = 8_000;

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<WebSocketSession> opened = new ArrayList<>();

    private String hostToken;
    private String hostProfileId;
    private String friendToken;
    private String friendProfileId;
    private String code;

    @BeforeEach
    void setUp() throws Exception {
        String[] host = registerWithProfile("sock_host", "Amma");
        hostToken = host[0];
        hostProfileId = host[1];

        String[] friend = registerWithProfile("sock_friend", "Ravi");
        friendToken = friend[0];
        friendProfileId = friend[1];

        MediaItem film = insertFilm("Socket Film");
        HttpResponse<String> created = send("POST", "/api/parties",
                "{\"mediaItemId\":\"%s\"}".formatted(film.getId()), hostToken, hostProfileId);
        assertEquals(201, created.statusCode(), created.body());
        code = extract(created.body(), "code");

        assertEquals(200, send("POST", "/api/parties/" + code + "/join",
                null, friendToken, friendProfileId).statusCode());
    }

    @AfterEach
    void closeSockets() {
        for (WebSocketSession session : opened) {
            try {
                session.close();
            } catch (Exception ignored) {
                // The test is over; a socket the server already closed is not a failure.
            }
        }
        opened.clear();
    }

    // --- handshake ---

    @Test
    void connectingDeliversTheClockWithoutWaitingForTheFirstTick() throws Exception {
        Socket member = connect(friendToken);

        String tick = member.await("tick");
        assertTrue(tick.contains("\"state\":\"PAUSED\""), tick);
        assertTrue(tick.contains("\"serverNowEpochMillis\""), tick);

        String members = member.await("members");
        assertTrue(members.contains("\"name\":\"Amma\""), members);
        assertTrue(members.contains("\"name\":\"Ravi\""), members);
    }

    @Test
    void handshakeRefusesABadTokenAndANonMember() throws Exception {
        assertThrows(ExecutionException.class, () -> connect("not-a-token"));

        String[] stranger = registerWithProfile("sock_stranger", "Nobody");
        assertThrows(ExecutionException.class, () -> connect(stranger[0]));
    }

    @Test
    void handshakeRefusesAnEndedParty() throws Exception {
        assertEquals(204, send("DELETE", "/api/parties/" + code,
                null, hostToken, hostProfileId).statusCode());

        assertThrows(ExecutionException.class, () -> connect(friendToken));
    }

    // --- the clock ---

    @Test
    void hostPauseReachesTheOtherDeviceWithThePositionItHappenedAt() throws Exception {
        Socket host = connect(hostToken);
        Socket member = connect(friendToken);
        host.await("tick");
        member.await("tick");

        host.send("{\"type\":\"play\",\"positionSeconds\":800.0}");
        String playing = member.await("play");
        assertTrue(playing.contains("\"state\":\"PLAYING\""), playing);
        assertTrue(playing.contains("\"by\":\"Amma\""), playing);

        host.send("{\"type\":\"pause\",\"positionSeconds\":812.5}");
        String paused = member.await("pause");
        assertTrue(paused.contains("\"state\":\"PAUSED\""), paused);
        assertTrue(paused.contains("\"positionSeconds\":812.5"), paused);
    }

    @Test
    void seekMovesTheAnchorAndTheNextTickAgreesWithIt() throws Exception {
        Socket host = connect(hostToken);
        Socket member = connect(friendToken);
        host.await("tick");
        member.await("tick");

        host.send("{\"type\":\"seek\",\"positionSeconds\":1043.0}");
        assertTrue(member.await("seek").contains("\"positionSeconds\":1043.0"));

        // Still paused, so the timed tick must report the same frame rather than
        // having advanced with the wall clock.
        assertTrue(member.await("tick").contains("\"positionSeconds\":1043.0"));
    }

    @Test
    void aMemberCannotDriveThePlayhead() throws Exception {
        Socket host = connect(hostToken);
        Socket member = connect(friendToken);
        host.await("tick");
        member.await("tick");

        host.send("{\"type\":\"pause\",\"positionSeconds\":300.0}");
        member.await("pause");

        member.send("{\"type\":\"play\",\"positionSeconds\":9000.0}");

        String refusal = member.await("error");
        assertTrue(refusal.contains("only the host"), refusal);

        // The refusal has to be real, not just a message: the clock stayed put.
        String tick = member.await("tick");
        assertTrue(tick.contains("\"state\":\"PAUSED\""), tick);
        assertTrue(tick.contains("\"positionSeconds\":300.0"), tick);
    }

    @Test
    void anUnreadableFrameIsRefusedWithoutDroppingTheSocket() throws Exception {
        Socket member = connect(friendToken);
        member.await("tick");

        member.send("this is not json");
        assertNotNull(member.await("error"));

        // Still connected: the next tick arrives as normal.
        assertNotNull(member.await("tick"));
    }

    // --- who is here ---

    @Test
    void theRestFallbackReportsTheSameClockAndOnlineFlags() throws Exception {
        Socket host = connect(hostToken);
        host.await("tick");
        host.send("{\"type\":\"play\",\"positionSeconds\":55.0}");
        host.await("play");

        HttpResponse<String> state = send("GET", "/api/parties/" + code + "/state",
                null, hostToken, hostProfileId);
        assertEquals(200, state.statusCode(), state.body());
        assertTrue(state.body().contains("\"state\":\"PLAYING\""), state.body());
        assertTrue(state.body().contains("\"positionSeconds\":55.0"), state.body());
        // The host is connected; the friend joined over REST but never opened a socket.
        assertTrue(state.body().contains("\"name\":\"Amma\",\"role\":\"HOST\",\"host\":true"),
                state.body());
        assertEquals(1, countOccurrences(state.body(), "\"online\":true"), state.body());
        assertEquals(1, countOccurrences(state.body(), "\"online\":false"), state.body());
    }

    @Test
    void theHostDroppingPausesThePartyRatherThanEndingIt() throws Exception {
        Socket host = connect(hostToken);
        Socket member = connect(friendToken);
        host.await("tick");
        member.await("tick");

        host.send("{\"type\":\"play\",\"positionSeconds\":120.0}");
        member.await("play");

        host.session.close();

        String paused = member.await("pause");
        assertTrue(paused.contains("\"state\":\"PAUSED\""), paused);
        assertTrue(paused.contains("host disconnected"), paused);

        // The party itself is still live, so the member is still being served.
        assertEquals(200, send("GET", "/api/parties/" + code + "/state",
                null, friendToken, friendProfileId).statusCode());
        assertNotNull(member.await("tick"));
    }

    @Test
    void endingThePartyTellsEveryoneBeforeClosingTheirSocket() throws Exception {
        Socket member = connect(friendToken);
        member.await("tick");

        assertEquals(204, send("DELETE", "/api/parties/" + code,
                null, hostToken, hostProfileId).statusCode());

        String ended = member.await("ended");
        assertTrue(ended.contains("host ended it"), ended);
    }

    // --- plumbing ---

    /** One open client socket and the frames it has been sent. */
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

        /** Waits for the next frame of a type, skipping whatever else arrives first. */
        private String await(String type) throws Exception {
            String needle = "\"type\":\"" + type + "\"";
            long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                String frame = frames.poll(250, TimeUnit.MILLISECONDS);
                if (frame != null && frame.contains(needle)) {
                    return frame;
                }
            }
            throw new AssertionError("no '" + type + "' frame arrived within "
                    + FRAME_TIMEOUT_MILLIS + "ms");
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
        // The handler and the Socket share one queue, so frames that arrive during the
        // handshake are waiting when the first await() runs.
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
                 "password":"pw123456","displayName":"Socket Tester"}
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
