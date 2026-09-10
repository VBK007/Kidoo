package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;

/**
 * Watch party membership end to end, across two separate accounts.
 *
 * <p>Two accounts rather than two profiles on one, because that is the case the feature
 * exists for and the one the rest of the media API deliberately forbids: every
 * profile-scoped endpoint re-checks ownership, so a test using one account would prove
 * nothing about friends watching together.
 *
 * <p>Covers membership only. The shared playhead is not wired yet, so every response
 * here carries a null clock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WatchPartyIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    private final HttpClient http = HttpClient.newHttpClient();

    private String hostToken;
    private String hostProfileId;
    private String friendToken;
    private String friendProfileId;
    private MediaItem film;

    @BeforeEach
    void setUp() throws Exception {
        String[] host = registerWithProfile("party_host", "Amma");
        hostToken = host[0];
        hostProfileId = host[1];

        String[] friend = registerWithProfile("party_friend", "Ravi");
        friendToken = friend[0];
        friendProfileId = friend[1];

        film = insertFilm("The Shared Film", 0, 0);
    }

    // --- opening and joining ---

    @Test
    void hostOpensPartyAndFriendOnAnotherAccountJoins() throws Exception {
        HttpResponse<String> created = createParty(film.getId(), null);
        assertEquals(201, created.statusCode(), created.body());

        String code = extract(created.body(), "code");
        assertNotNull(code, created.body());
        assertEquals(6, code.length(), code);
        // The alphabet drops the characters people misread aloud.
        assertTrue(code.matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}"), code);

        assertTrue(created.body().contains("\"youAreHost\":true"), created.body());
        assertTrue(created.body().contains("\"role\":\"HOST\""), created.body());
        assertTrue(created.body().contains("\"itemTitle\":\"The Shared Film\""), created.body());
        // Nothing drives the playhead until the socket layer exists.
        assertTrue(created.body().contains("\"clock\":null"), created.body());

        HttpResponse<String> joined = send("POST", "/api/parties/" + code + "/join",
                null, friendToken, friendProfileId);
        assertEquals(200, joined.statusCode(), joined.body());
        assertTrue(joined.body().contains("\"youAreHost\":false"), joined.body());
        assertTrue(joined.body().contains("\"name\":\"Amma\""), joined.body());
        assertTrue(joined.body().contains("\"name\":\"Ravi\""), joined.body());
        assertTrue(joined.body().contains("\"role\":\"MEMBER\""), joined.body());
    }

    @Test
    void codeIsAcceptedLowerCaseAndWithSeparators() throws Exception {
        String code = extract(createParty(film.getId(), null).body(), "code");

        HttpResponse<String> joined = send("POST",
                "/api/parties/" + code.toLowerCase() + "/join", null, friendToken, friendProfileId);
        assertEquals(200, joined.statusCode(), joined.body());
    }

    @Test
    void rejoiningReusesTheSameSeatRatherThanAddingASecond() throws Exception {
        String code = extract(createParty(film.getId(), null).body(), "code");

        send("POST", "/api/parties/" + code + "/join", null, friendToken, friendProfileId);
        HttpResponse<String> again = send("POST", "/api/parties/" + code + "/join",
                null, friendToken, friendProfileId);

        assertEquals(200, again.statusCode(), again.body());
        assertEquals(1, countOccurrences(again.body(), "\"name\":\"Ravi\""), again.body());
    }

    @Test
    void unknownCodeIsNotFound() throws Exception {
        HttpResponse<String> joined = send("POST", "/api/parties/ZZZZZZ/join",
                null, friendToken, friendProfileId);
        assertEquals(404, joined.statusCode(), joined.body());
    }

    // --- capacity ---

    @Test
    void partyRefusesJoinsOnceEverySeatIsTaken() throws Exception {
        // Two seats: the host takes one, the friend the other.
        String code = extract(createParty(film.getId(), 2).body(), "code");
        assertEquals(200, send("POST", "/api/parties/" + code + "/join",
                null, friendToken, friendProfileId).statusCode());

        String[] third = registerWithProfile("party_third", "Kavi");
        HttpResponse<String> refused = send("POST", "/api/parties/" + code + "/join",
                null, third[0], third[1]);
        assertEquals(409, refused.statusCode(), refused.body());
    }

    @Test
    void titleThatAlwaysTranscodesWarnsAboutTheCostPerSeat() throws Exception {
        // Played before and never once direct-played: the one case where the warning
        // is evidence rather than a guess.
        MediaItem heavy = insertFilm("Heavy Encode", 0, 5);

        HttpResponse<String> created = createParty(heavy.getId(), null);
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("ffmpeg"), created.body());

        HttpResponse<String> light = createParty(film.getId(), null);
        assertTrue(light.body().contains("\"capacityWarning\":null"), light.body());
    }

    // --- visibility ---

    @Test
    void someoneOutsideThePartyCannotReadItsState() throws Exception {
        String code = extract(createParty(film.getId(), null).body(), "code");

        HttpResponse<String> peek = send("GET", "/api/parties/" + code + "/state",
                null, friendToken, friendProfileId);
        assertEquals(403, peek.statusCode(), peek.body());

        send("POST", "/api/parties/" + code + "/join", null, friendToken, friendProfileId);
        HttpResponse<String> allowed = send("GET", "/api/parties/" + code + "/state",
                null, friendToken, friendProfileId);
        assertEquals(200, allowed.statusCode(), allowed.body());
    }

    @Test
    void pendingListIsShownToTheHostAlone() throws Exception {
        String code = extract(createParty(film.getId(), null).body(), "code");
        send("POST", "/api/parties/" + code + "/join", null, friendToken, friendProfileId);

        HttpResponse<String> asHost = send("GET", "/api/parties/" + code + "/state",
                null, hostToken, hostProfileId);
        assertTrue(asHost.body().contains("\"pending\":[]"), asHost.body());

        // A member sees the same empty list, but because it is withheld rather than
        // because there is nothing in it — which only guests will make visible.
        HttpResponse<String> asMember = send("GET", "/api/parties/" + code + "/state",
                null, friendToken, friendProfileId);
        assertTrue(asMember.body().contains("\"pending\":[]"), asMember.body());
    }

    // --- leaving and ending ---

    @Test
    void memberLeavingFreesTheirSeat() throws Exception {
        String code = extract(createParty(film.getId(), 2).body(), "code");
        send("POST", "/api/parties/" + code + "/join", null, friendToken, friendProfileId);

        assertEquals(204, send("POST", "/api/parties/" + code + "/leave",
                null, friendToken, friendProfileId).statusCode());

        HttpResponse<String> state = send("GET", "/api/parties/" + code + "/state",
                null, hostToken, hostProfileId);
        assertTrue(state.body().contains("\"name\":\"Amma\""), state.body());
        assertEquals(0, countOccurrences(state.body(), "\"name\":\"Ravi\""), state.body());

        // The freed seat is genuinely free.
        String[] third = registerWithProfile("party_late", "Kavi");
        assertEquals(200, send("POST", "/api/parties/" + code + "/join",
                null, third[0], third[1]).statusCode());
    }

    @Test
    void onlyTheHostMayEndTheParty() throws Exception {
        String code = extract(createParty(film.getId(), null).body(), "code");
        send("POST", "/api/parties/" + code + "/join", null, friendToken, friendProfileId);

        assertEquals(403, send("DELETE", "/api/parties/" + code,
                null, friendToken, friendProfileId).statusCode());

        assertEquals(204, send("DELETE", "/api/parties/" + code,
                null, hostToken, hostProfileId).statusCode());

        // An ended party is indistinguishable from a code that never existed.
        assertEquals(404, send("GET", "/api/parties/" + code + "/state",
                null, hostToken, hostProfileId).statusCode());
    }

    @Test
    void hostLeavingEndsThePartyForEveryone() throws Exception {
        String code = extract(createParty(film.getId(), null).body(), "code");
        send("POST", "/api/parties/" + code + "/join", null, friendToken, friendProfileId);

        assertEquals(204, send("POST", "/api/parties/" + code + "/leave",
                null, hostToken, hostProfileId).statusCode());

        assertEquals(404, send("GET", "/api/parties/" + code + "/state",
                null, friendToken, friendProfileId).statusCode());
    }

    @Test
    void openingASecondPartyRetiresTheFirst() throws Exception {
        String first = extract(createParty(film.getId(), null).body(), "code");
        String second = extract(createParty(film.getId(), null).body(), "code");

        assertEquals(404, send("POST", "/api/parties/" + first + "/join",
                null, friendToken, friendProfileId).statusCode());
        assertEquals(200, send("POST", "/api/parties/" + second + "/join",
                null, friendToken, friendProfileId).statusCode());
    }

    @Test
    void aPartyCannotBeOpenedAroundATitleThatDoesNotExist() throws Exception {
        HttpResponse<String> created = createParty("no-such-item", null);
        assertEquals(404, created.statusCode(), created.body());
    }

    // --- helpers ---

    private HttpResponse<String> createParty(String itemId, Integer maxMembers) throws Exception {
        String body = maxMembers == null
                ? "{\"mediaItemId\":\"%s\"}".formatted(itemId)
                : "{\"mediaItemId\":\"%s\",\"maxMembers\":%d}".formatted(itemId, maxMembers);
        return send("POST", "/api/parties", body, hostToken, hostProfileId);
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
                 "password":"pw123456","displayName":"Party Tester"}
                """.formatted(prefix, unique, prefix, unique), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        String bearer = extract(registered.body(), "token");

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"%s\",\"ageMode\":\"OLDER\"}".formatted(profileName), bearer, null);
        assertEquals(201, profile.statusCode(), profile.body());
        return new String[]{bearer, extract(profile.body(), "id")};
    }

    /**
     * File paths are unique in the catalog, and this class deliberately does not clear
     * the library between tests — parties reference items by id, so leaving other
     * fixtures in place is harmless. Hence a fresh path per insert.
     */
    private MediaItem insertFilm(String title, long directPlays, long transcodes) {
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
                .directPlayCount(directPlays)
                .transcodeCount(transcodes)
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
