package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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

/**
 * The home screen end to end: likes are recorded per profile, play counts and ratings
 * are read off the catalog, and the rails come back in the order the ranker decided.
 *
 * <p>Rows are inserted directly rather than by playing files, because what is under
 * test is the ranking and its wiring, not the streaming path — and the test profile
 * configures no media library, so there is nothing on disk to play.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaHomeIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    @Autowired
    MediaItemLikeRepository likes;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private String profileId;

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> send(String method, String path, String json, String bearer)
            throws Exception {
        return send(method, path, json, bearer, profileId);
    }

    private HttpResponse<String> send(String method, String path, String json,
                                      String bearer, String profile) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path));
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

    private String[] registerWithProfile(String prefix) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"%s_%s","email":"%s_%s@example.com",
                 "password":"pw123456","displayName":"Home Tester"}
                """.formatted(prefix, unique, prefix, unique), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        String bearer = extract(registered.body(), "token");

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"Tester\",\"ageMode\":\"OLDER\"}", bearer, null);
        assertEquals(201, profile.statusCode(), profile.body());
        return new String[]{bearer, extract(profile.body(), "id")};
    }

    /**
     * The catalog is shared by the whole suite and the rails assert on order, so this
     * class starts from an empty library rather than ranking other tests' fixtures.
     */
    @BeforeEach
    void setUp() throws Exception {
        likes.deleteAll();
        items.deleteAll();
        String[] credentials = registerWithProfile("home");
        token = credentials[0];
        profileId = credentials[1];
    }

    private MediaItem insertFilm(String title, Double rating, long directPlays,
                                 long transcodes) {
        return items.save(MediaItem.builder()
                .type(MediaType.FILM)
                .libraryName("Films")
                .filePath("D:/Media/" + title.replace(' ', '.') + ".mkv")
                .fileName(title.replace(' ', '.') + ".mkv")
                .folderPath("D:/Media")
                .fileSize(1_400_000_000L)
                .title(title)
                .sortTitle(title.toLowerCase())
                .year(2015)
                .runtimeMinutes(120)
                .rating(rating)
                .directPlayCount(directPlays)
                .transcodeCount(transcodes)
                .build());
    }

    // --- likes ---

    @Test
    void likeIsIdempotentAndCounted() throws Exception {
        MediaItem film = insertFilm("Likeable", 7.0, 0, 0);

        HttpResponse<String> first = send("PUT", "/api/media/items/" + film.getId() + "/like",
                null, token);
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.body().contains("\"liked\":true"), first.body());
        assertTrue(first.body().contains("\"likeCount\":1"), first.body());

        // A retried tap must not become a second like.
        HttpResponse<String> again = send("PUT", "/api/media/items/" + film.getId() + "/like",
                null, token);
        assertTrue(again.body().contains("\"likeCount\":1"), again.body());

        HttpResponse<String> removed = send("DELETE",
                "/api/media/items/" + film.getId() + "/like", null, token);
        assertTrue(removed.body().contains("\"liked\":false"), removed.body());
        assertTrue(removed.body().contains("\"likeCount\":0"), removed.body());

        // Unliking something never liked is not an error either.
        HttpResponse<String> spurious = send("DELETE",
                "/api/media/items/" + film.getId() + "/like", null, token);
        assertEquals(200, spurious.statusCode(), spurious.body());
    }

    /** The denormalised total is what ranking sorts on, so it has to track the rows. */
    @Test
    void likeCountOnTheItemTracksTheLikeRows() throws Exception {
        MediaItem film = insertFilm("Counted", 7.0, 0, 0);
        send("PUT", "/api/media/items/" + film.getId() + "/like", null, token);

        assertEquals(1, items.findById(film.getId()).orElseThrow().getLikeCount());

        send("DELETE", "/api/media/items/" + film.getId() + "/like", null, token);
        assertEquals(0, items.findById(film.getId()).orElseThrow().getLikeCount());
    }

    /** One household member's taste must not fill in another's heart. */
    @Test
    void likedFlagIsPerProfileWhileTheTotalIsShared() throws Exception {
        MediaItem film = insertFilm("Shared", 7.0, 0, 0);
        send("PUT", "/api/media/items/" + film.getId() + "/like", null, token);

        String[] other = registerWithProfile("other");
        HttpResponse<String> theirs = send("GET", "/api/media/items/" + film.getId() + "/like",
                null, other[0], other[1]);
        assertEquals(200, theirs.statusCode(), theirs.body());
        assertTrue(theirs.body().contains("\"liked\":false"), theirs.body());
        // The count is the household's, so they still see that someone liked it.
        assertTrue(theirs.body().contains("\"likeCount\":1"), theirs.body());
    }

    @Test
    void likeRequiresAuthenticationAndAKnownItem() throws Exception {
        MediaItem film = insertFilm("Guarded", 7.0, 0, 0);
        assertEquals(403, send("PUT", "/api/media/items/" + film.getId() + "/like",
                null, null).statusCode());
        assertEquals(404, send("PUT", "/api/media/items/does-not-exist/like",
                null, token).statusCode());
    }

    // --- home screen ---

    @Test
    void homeReturnsEveryRailAndTheWeightsBehindThem() throws Exception {
        insertFilm("Acclaimed", 9.2, 1, 0);
        insertFilm("Rewatched", 6.5, 30, 10);
        MediaItem loved = insertFilm("Loved", 6.0, 2, 0);
        send("PUT", "/api/media/items/" + loved.getId() + "/like", null, token);

        HttpResponse<String> home = send("GET", "/api/media/home", null, token);
        assertEquals(200, home.statusCode(), home.body());

        for (String rail : List.of("popular", "top-rated", "most-watched", "most-liked",
                "recently-added")) {
            assertTrue(home.body().contains("\"key\":\"" + rail + "\""),
                    "missing rail " + rail + " in " + home.body());
        }
        assertTrue(home.body().contains("\"continueWatching\""), home.body());
        assertTrue(home.body().contains("\"weights\""), home.body());
        assertTrue(home.body().contains("\"generatedAt\""), home.body());
    }

    /** Each single-signal rail must be led by the title that actually leads that signal. */
    @Test
    void eachRailIsLedByItsOwnSignal() throws Exception {
        insertFilm("Acclaimed", 9.2, 1, 0);
        insertFilm("Rewatched", 6.5, 30, 10);
        MediaItem loved = insertFilm("Loved", 6.0, 2, 0);
        send("PUT", "/api/media/items/" + loved.getId() + "/like", null, token);

        String body = send("GET", "/api/media/home", null, token).body();

        assertEquals("Acclaimed", firstTitleOfRail(body, "top-rated"));
        assertEquals("Rewatched", firstTitleOfRail(body, "most-watched"));
        assertEquals("Loved", firstTitleOfRail(body, "most-liked"));
    }

    /**
     * The point of the blend: a title that leads on nothing outright but is decent on
     * all three beats one that is only well rated.
     */
    @Test
    void popularRailBlendsRatingViewsAndLikes() throws Exception {
        insertFilm("PristineButIgnored", 9.0, 0, 0);
        MediaItem allRound = insertFilm("AllRound", 7.6, 25, 0);
        send("PUT", "/api/media/items/" + allRound.getId() + "/like", null, token);

        String body = send("GET", "/api/media/home/popular", null, token).body();
        List<String> order = titlesOfRail(body);

        assertEquals("AllRound", order.get(0), body);
        assertTrue(order.contains("PristineButIgnored"), body);
    }

    /** Unrated home footage still has to be rankable, or the rail could never show it. */
    @Test
    void unratedItemsStillRank() throws Exception {
        MediaItem unrated = insertFilm("NoSidecarRating", null, 40, 0);
        insertFilm("Rated", 7.0, 0, 0);
        send("PUT", "/api/media/items/" + unrated.getId() + "/like", null, token);

        String body = send("GET", "/api/media/home/popular", null, token).body();
        assertEquals("NoSidecarRating", titlesOfRail(body).get(0), body);
    }

    /** An empty library must return an empty home screen, not a failure. */
    @Test
    void emptyLibraryReturnsEmptyRails() throws Exception {
        HttpResponse<String> home = send("GET", "/api/media/home", null, token);
        assertEquals(200, home.statusCode(), home.body());
        assertTrue(home.body().contains("\"rails\":[]"), home.body());
    }

    @Test
    void homeRequiresAuthentication() throws Exception {
        assertEquals(403, send("GET", "/api/media/home", null, null).statusCode());
    }

    /** Tiles carry the ranking signals, so a grid can render them without extra calls. */
    @Test
    void tilesCarryViewAndLikeCounts() throws Exception {
        MediaItem film = insertFilm("Instrumented", 8.0, 3, 2);
        send("PUT", "/api/media/items/" + film.getId() + "/like", null, token);

        String body = send("GET", "/api/media/items", null, token).body();
        assertTrue(body.contains("\"viewCount\":5"), body);
        assertTrue(body.contains("\"likeCount\":1"), body);
        assertTrue(body.contains("\"liked\":true"), body);
    }

    @Test
    void libraryCanBeSortedByLikes() throws Exception {
        insertFilm("Unloved", 9.5, 0, 0);
        MediaItem loved = insertFilm("Adored", 5.0, 0, 0);
        send("PUT", "/api/media/items/" + loved.getId() + "/like", null, token);

        String body = send("GET", "/api/media/items?sort=likes", null, token).body();
        assertTrue(body.indexOf("Adored") < body.indexOf("Unloved"), body);

        assertEquals(400, send("GET", "/api/media/items?sort=nonsense", null, token)
                .statusCode());
    }

    // --- helpers ---

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Slices one rail out of the home payload by its key, then reads its titles. */
    private static String firstTitleOfRail(String json, String key) {
        List<String> titles = titlesOfRail(railBody(json, key));
        return titles.isEmpty() ? null : titles.get(0);
    }

    private static String railBody(String json, String key) {
        int start = json.indexOf("\"key\":\"" + key + "\"");
        if (start < 0) {
            return "";
        }
        int next = json.indexOf("\"key\":\"", start + 1);
        return next < 0 ? json.substring(start) : json.substring(start, next);
    }

    /**
     * Item titles, in rail order. Anchored on the {@code year} that follows a tile's
     * title so the rail's own heading — also a {@code title} — is not read as a poster.
     */
    private static List<String> titlesOfRail(String railJson) {
        Matcher matcher = Pattern
                .compile("\"title\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"year\"")
                .matcher(railJson);
        List<String> titles = new ArrayList<>();
        while (matcher.find()) {
            titles.add(matcher.group(1));
        }
        return titles;
    }
}
