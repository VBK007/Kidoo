package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
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
import com.example.kido.media.engagement.MediaItemLikeRepository;
import com.example.kido.media.session.WatchEventRepository;

/**
 * The artists grid and an artist's page: one credit line naming several singers has to
 * reach every one of their tiles, and a tile prefers a track that actually has a poster.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaArtistIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    @Autowired
    MediaItemLikeRepository likes;

    @Autowired
    WatchEventRepository watchEvents;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private String profileId;

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> send(String method, String path, String json, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (profileId != null) {
            builder.header("X-Profile-Id", profileId);
        }
        builder.method(method, json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @BeforeEach
    void setUp() throws Exception {
        likes.deleteAll();
        watchEvents.deleteAll();
        items.deleteAll();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"art_%s","email":"art_%s@example.com",
                 "password":"pw123456","displayName":"Artist Tester"}
                """.formatted(unique, unique), null);
        assertEquals(201, registered.statusCode(), registered.body());
        token = extract(registered.body(), "token");

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"Tester\",\"ageMode\":\"OLDER\"}", token);
        assertEquals(201, profile.statusCode(), profile.body());
        profileId = extract(profile.body(), "id");
    }

    private MediaItem insertTrack(String title, String artistCredit, Set<String> artistNames, String poster) {
        return items.save(MediaItem.builder()
                .type(MediaType.MUSIC)
                .libraryName("Music")
                .filePath("D:/Media/" + UUID.randomUUID() + "/" + title.replace(' ', '.') + ".mp3")
                .fileName(title.replace(' ', '.') + ".mp3")
                .folderPath("D:/Media")
                .fileSize(4_000_000L)
                .title(title)
                .sortTitle(title.toLowerCase())
                .artist(artistCredit)
                .artistNames(new LinkedHashSet<>(artistNames))
                .posterPath(poster)
                .build());
    }

    @Test
    void aCollaborationCreditsEverySingerNamedInIt() throws Exception {
        insertTrack("Chaleya", "Anirudh Ravichander, Arijit Singh",
                Set.of("Anirudh Ravichander", "Arijit Singh"), null);
        insertTrack("Aaluma Doluma", "Anirudh Ravichander, Badshah",
                Set.of("Anirudh Ravichander", "Badshah"), null);

        String body = send("GET", "/api/media/artists", null, token).body();

        assertTrue(body.contains("\"name\":\"Anirudh Ravichander\""), body);
        assertTrue(body.contains("\"trackCount\":2"), body);
        assertTrue(body.contains("\"name\":\"Arijit Singh\""), body);
        assertTrue(body.contains("\"name\":\"Badshah\""), body);
    }

    @Test
    void artistTilePrefersATrackThatHasAPoster() throws Exception {
        insertTrack("No Poster Song", "Solo Singer", Set.of("Solo Singer"), null);
        insertTrack("Has Poster Song", "Solo Singer", Set.of("Solo Singer"), "/data/posters/x.jpg");

        String body = send("GET", "/api/media/artists", null, token).body();
        int nameAt = body.indexOf("\"name\":\"Solo Singer\"");
        String artistJson = body.substring(nameAt, body.indexOf('}', nameAt) + 1);

        MediaItem withPoster = items.findAll().stream()
                .filter(MediaItem::hasPoster).findFirst().orElseThrow();
        assertTrue(artistJson.contains(withPoster.getId()), artistJson);
    }

    @Test
    void artistPageReturnsTheirFullCatalog() throws Exception {
        insertTrack("Song A", "The Duo", Set.of("The Duo"), null);
        insertTrack("Song B", "The Duo", Set.of("The Duo"), null);
        insertTrack("Unrelated", "Someone Else", Set.of("Someone Else"), null);

        // Path segments need %20 for a space, not URLEncoder's form-style "+".
        String encoded = URLEncoder.encode("The Duo", StandardCharsets.UTF_8).replace("+", "%20");
        String body = send("GET", "/api/media/artists/" + encoded, null, token).body();

        assertTrue(body.contains("\"trackCount\":2"), body);
        assertTrue(body.contains("\"title\":\"Song A\""), body);
        assertTrue(body.contains("\"title\":\"Song B\""), body);
        assertFalse(body.contains("\"title\":\"Unrelated\""), body);
    }

    @Test
    void unknownArtistIs404() throws Exception {
        HttpResponse<String> response = send("GET", "/api/media/artists/Nobody", null, token);
        assertEquals(404, response.statusCode());
    }

    @Test
    void artistsRequireAuthentication() throws Exception {
        assertEquals(403, send("GET", "/api/media/artists", null, null).statusCode());
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
