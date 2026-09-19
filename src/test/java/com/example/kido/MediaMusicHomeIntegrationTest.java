package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.example.kido.media.engagement.MediaItemLikeRepository;
import com.example.kido.media.session.WatchEventRepository;

/**
 * The music tab's home screen end to end: rows are inserted directly with mood/activity/
 * musicDirector/year already set, because what is under test is the rail assembly and
 * the sparse-facet cutoff, not {@code AudioFeatureService}'s own decoding — that would
 * need a real audio file and is exercised separately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaMusicHomeIntegrationTest {

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
                {"username":"music_%s","email":"music_%s@example.com",
                 "password":"pw123456","displayName":"Music Tester"}
                """.formatted(unique, unique), null);
        assertEquals(201, registered.statusCode(), registered.body());
        token = extract(registered.body(), "token");

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"Tester\",\"ageMode\":\"OLDER\"}", token);
        assertEquals(201, profile.statusCode(), profile.body());
        profileId = extract(profile.body(), "id");
    }

    private MediaItem insertTrack(String title, MediaType type, String mood, String activity,
                                  String musicDirector, Integer year) {
        return items.save(MediaItem.builder()
                .type(type)
                .libraryName("Music")
                .filePath("D:/Media/" + UUID.randomUUID() + "/" + title.replace(' ', '.') + ".mp3")
                .fileName(title.replace(' ', '.') + ".mp3")
                .folderPath("D:/Media")
                .fileSize(4_000_000L)
                .title(title)
                .sortTitle(title.toLowerCase())
                .artist("Some Singer")
                .album("Some Movie")
                .musicDirector(musicDirector)
                .mood(mood)
                .activity(activity)
                .year(year)
                .build());
    }

    @Test
    void musicHomeOmitsFacetsBelowTheMinimumRailSize() throws Exception {
        // Three Romantic tracks clears the minimum; two Sad tracks does not.
        insertTrack("R1", MediaType.MUSIC, "Romantic", "Relax", "Composer A", 2015);
        insertTrack("R2", MediaType.MUSIC, "Romantic", "Relax", "Composer A", 2015);
        insertTrack("R3", MediaType.MUSIC, "Romantic", "Relax", "Composer A", 2015);
        insertTrack("R4", MediaType.MUSIC, "Romantic", "Relax", "Composer A", 2015);
        insertTrack("S1", MediaType.MUSIC, "Sad", "Relax", "Composer B", 2015);
        insertTrack("S2", MediaType.MUSIC, "Sad", "Relax", "Composer B", 2015);

        String body = send("GET", "/api/media/home/music", null, token).body();

        assertTrue(body.contains("\"key\":\"mood:Romantic\""), body);
        assertFalse(body.contains("\"key\":\"mood:Sad\""), body);
    }

    @Test
    void musicHomeBuildsDirectorAndEraRails() throws Exception {
        for (int i = 0; i < 4; i++) {
            insertTrack("Old " + i, MediaType.VIDEO_SONG, "Chill", "Travel", "Ilaiyaraaja", 1995);
        }
        for (int i = 0; i < 4; i++) {
            insertTrack("New " + i, MediaType.VIDEO_SONG, "Energetic", "Party", "Anirudh Ravichander", 2018);
        }

        String body = send("GET", "/api/media/home/music", null, token).body();

        assertTrue(body.contains("\"key\":\"director:Ilaiyaraaja\""), body);
        assertTrue(body.contains("\"key\":\"director:Anirudh Ravichander\""), body);
        assertTrue(body.contains("\"key\":\"era:1990\""), body);
        assertTrue(body.contains("\"key\":\"era:2010\""), body);
        assertTrue(body.contains("\"title\":\"Best of the 1990s\""), body);
    }

    @Test
    void musicHomeIsScopedToAudioTypesOnly() throws Exception {
        items.save(MediaItem.builder()
                .type(MediaType.FILM)
                .libraryName("Films")
                .filePath("D:/Media/notmusic.mkv")
                .fileName("notmusic.mkv")
                .fileSize(1_000_000L)
                .title("Not Music")
                .sortTitle("not music")
                .build());
        insertTrack("Track", MediaType.MUSIC, "Chill", "Relax", "Composer A", 2015);

        String body = send("GET", "/api/media/home/music", null, token).body();

        assertFalse(body.contains("Not Music"), body);
    }

    @Test
    void emptyMusicLibraryReturnsEmptyRails() throws Exception {
        HttpResponse<String> home = send("GET", "/api/media/home/music", null, token);
        assertEquals(200, home.statusCode(), home.body());
        assertTrue(home.body().contains("\"rails\":[]"), home.body());
        assertTrue(home.body().contains("\"continueListening\":[]"), home.body());
    }

    @Test
    void musicHomeRequiresAuthentication() throws Exception {
        assertEquals(403, send("GET", "/api/media/home/music", null, null).statusCode());
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
