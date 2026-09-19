package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.collection.CollectionQueryCodec;
import com.example.kido.media.collection.MediaCollectionRepository;
import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.query.CatalogQuery.Range;
import com.example.kido.media.query.CatalogQuery.WatchedBy;

/**
 * Collections end to end: the ones that ship, the ones an owner writes, and the ones
 * the library turns out to contain.
 *
 * <p>The round trip through JSON gets its own attention. A stored query is the one place
 * a {@link CatalogQuery} leaves the process and comes back, so a field the codec cannot
 * carry would show up as a collection that quietly returns the wrong films rather than
 * as an error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaCollectionIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    @Autowired
    MediaCollectionRepository collections;

    @Autowired
    CollectionQueryCodec codec;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private String profileId;

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        return send(method, path, json, token, profileId);
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

    @BeforeEach
    void setUp() throws Exception {
        collections.deleteAll();
        items.deleteAll();

        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"col_%s","email":"col_%s@example.com","password":"pw123456",
                 "displayName":"Collection Tester"}
                """.formatted(unique, unique), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        token = extract(registered.body(), "token");

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"Tester\",\"ageMode\":\"OLDER\"}", token, null);
        assertEquals(201, profile.statusCode(), profile.body());
        profileId = extract(profile.body(), "id");
    }

    private MediaItem film(String title, Double rating, Integer runtime, Integer height,
                           Set<String> genres, String language) {
        MediaItem item = MediaItem.builder()
                .type(MediaType.FILM)
                .filePath("D:/Media/" + title.replace(' ', '.') + ".mkv")
                .fileName(title.replace(' ', '.') + ".mkv")
                .title(title)
                .sortTitle(title.toLowerCase())
                .year(2019)
                .runtimeMinutes(runtime)
                .rating(rating)
                .mediaInfo(MediaInfo.builder().height(height).build())
                .build();
        if (genres != null) {
            item.getGenres().addAll(genres);
        }
        if (language != null) {
            item.getLanguages().add(language);
            item.setPrimaryLanguage(language);
        }
        return items.save(item);
    }

    // --- the query survives storage ---

    /**
     * The one place a query leaves the process and comes back. A field the codec drops
     * would not fail — it would widen the search under the name somebody gave it.
     */
    @Test
    void everyFilterSurvivesTheJsonRoundTrip() {
        CatalogQuery original = CatalogQuery.builder()
                .types(Set.of(MediaType.FILM, MediaType.ANIME))
                .titleContains("kaithi")
                .genres(Set.of("action", "thriller"))
                .genreMatch(CatalogQuery.MatchMode.ALL)
                .people(Set.of("rajinikanth"))
                .languages(Set.of("ta"))
                .primaryLanguageOnly(true)
                .year(Range.between(1990, 1999))
                .runtimeMinutes(Range.atMost(120))
                .rating(Range.atLeast(8.0))
                .minHeight(2160)
                .watched(WatchedBy.NOBODY)
                .liked(true)
                .sort("rating")
                .build()
                .validated();

        CatalogQuery reloaded = codec.read(codec.write(original), "test");

        assertEquals(original, reloaded);
    }

    /** A row from a newer build must fail loudly rather than run a wider search quietly. */
    @Test
    void anUnreadableStoredQueryIsAnErrorNotAGuess() {
        assertTrue(assertThrowsMessage(() ->
                codec.read("{\"filterWeHaveNotInventedYet\":true}", "From The Future"))
                .contains("newer version"));
    }

    // --- built-ins ---

    @Test
    void builtinsExistWithoutAnyRowsBehindThem() throws Exception {
        film("Unseen", 9.0, 100, 2160, Set.of("Action"), "ta");

        HttpResponse<String> response = send("GET", "/api/media/collections", null);
        assertEquals(200, response.statusCode(), response.body());

        assertEquals(0, collections.count(), "a fresh account stores no collection rows");
        for (String key : List.of("never-watched", "under-two-hours", "four-k",
                "highly-rated", "hidden-gems", "this-year", "our-own")) {
            assertTrue(response.body().contains("builtin:" + key),
                    "missing built-in " + key + " in " + response.body());
        }
    }

    /** A collection is a live query, so its count is what the library holds right now. */
    @Test
    void builtinsCountWhatTheyActuallyHold() throws Exception {
        film("Short4K", 9.0, 100, 2160, null, null);
        film("Long1080", 6.0, 200, 1080, null, null);

        String body = send("GET", "/api/media/collections/builtin:four-k", null).body();
        assertTrue(body.contains("\"itemCount\":1"), body);

        String underTwo = send("GET", "/api/media/collections/builtin:under-two-hours",
                null).body();
        assertTrue(underTwo.contains("\"itemCount\":1"), underTwo);
    }

    /** Favourites is the one builtin that is personal rather than a fact about the disk. */
    @Test
    void favouritesHoldsExactlyWhatThisProfileLiked() throws Exception {
        MediaItem loved = film("Loved", 6.0, 100, 1080, null, null);
        film("NotLoved", 9.0, 100, 1080, null, null);
        send("PUT", "/api/media/items/" + loved.getId() + "/like", null);

        String body = send("GET", "/api/media/collections/builtin:favourites/items", null).body();
        assertTrue(body.contains("Loved"), body);
        assertFalse(body.contains("NotLoved"), body);
    }

    /** One household member's taste must not fill in another's favourites. */
    @Test
    void favouritesArePerProfileNotPerHousehold() throws Exception {
        MediaItem film = film("SharedFilm", 6.0, 100, 1080, null, null);
        send("PUT", "/api/media/items/" + film.getId() + "/like", null);

        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"fav_%s","email":"fav_%s@example.com",
                 "password":"pw123456","displayName":"Other Person"}
                """.formatted(unique, unique), null, null);
        String otherToken = extract(registered.body(), "token");
        HttpResponse<String> otherProfile = send("POST", "/api/profiles",
                "{\"name\":\"Other\",\"ageMode\":\"OLDER\"}", otherToken, null);
        String otherProfileId = extract(otherProfile.body(), "id");

        String body = send("GET", "/api/media/collections/builtin:favourites/items",
                null, otherToken, otherProfileId).body();
        assertFalse(body.contains("SharedFilm"), body);
    }

    @Test
    void openingABuiltinListsItsTitles() throws Exception {
        film("Short4K", 9.0, 100, 2160, null, null);
        film("Long1080", 6.0, 200, 1080, null, null);

        String body = send("GET", "/api/media/collections/builtin:four-k/items", null).body();
        assertTrue(body.contains("Short4K"), body);
        assertFalse(body.contains("Long1080"), body);
    }

    /** The first pin is what creates the row; that is why a fresh account has none. */
    @Test
    void pinningABuiltinCreatesItsRowAndNothingElse() throws Exception {
        HttpResponse<String> pinned = send("PUT", "/api/media/collections/builtin:four-k",
                "{\"pinned\":true}");
        assertEquals(200, pinned.statusCode(), pinned.body());
        assertTrue(pinned.body().contains("\"pinned\":true"), pinned.body());
        assertEquals(1, collections.count());

        // Deleting restores it rather than removing it — it ships with the server.
        assertEquals(204, send("DELETE", "/api/media/collections/builtin:four-k", null)
                .statusCode());
        assertEquals(0, collections.count());
        assertTrue(send("GET", "/api/media/collections", null).body().contains("builtin:four-k"));
    }

    @Test
    void aBuiltinCanBeRenamedButNotRedefined() throws Exception {
        HttpResponse<String> renamed = send("PUT", "/api/media/collections/builtin:four-k",
                "{\"name\":\"Ultra HD\"}");
        assertEquals(200, renamed.statusCode(), renamed.body());
        assertTrue(renamed.body().contains("Ultra HD"), renamed.body());

        HttpResponse<String> redefined = send("PUT", "/api/media/collections/builtin:four-k",
                "{\"query\":{\"minHeight\":720}}");
        assertEquals(400, redefined.statusCode(), redefined.body());
        assertTrue(redefined.body().contains("built in"), redefined.body());
    }

    // --- custom ---

    @Test
    void anOwnerCanSaveAQueryAndOpenItLater() throws Exception {
        film("Wanted", 9.0, 100, 2160, Set.of("Action"), "ta");
        film("TooDull", 4.0, 100, 2160, Set.of("Action"), "ta");

        HttpResponse<String> created = send("POST", "/api/media/collections", """
                {"name":"Great Tamil action","icon":"🔥",
                 "query":{"genres":["action"],"languages":["ta"],
                          "primaryLanguageOnly":true,"rating":{"min":8.0}}}
                """);
        assertEquals(201, created.statusCode(), created.body());
        String id = extract(created.body(), "id");
        assertNotNull(id);
        assertTrue(created.body().contains("\"itemCount\":1"), created.body());

        String listed = send("GET", "/api/media/collections/" + id + "/items", null).body();
        assertTrue(listed.contains("Wanted"), listed);
        assertFalse(listed.contains("TooDull"), listed);
    }

    @Test
    void aCollectionNeedsBothANameAndAQuery() throws Exception {
        assertEquals(400, send("POST", "/api/media/collections",
                "{\"name\":\"No query\"}").statusCode());
        assertEquals(400, send("POST", "/api/media/collections",
                "{\"query\":{\"minHeight\":2160}}").statusCode());
    }

    /** An impossible filter is a typo, and an empty grid would look like an empty library. */
    @Test
    void anImpossibleQueryIsRejectedOnTheWayIn() throws Exception {
        HttpResponse<String> response = send("POST", "/api/media/collections",
                "{\"name\":\"Backwards\",\"query\":{\"year\":{\"min\":2020,\"max\":2010}}}");
        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    void aCollectionCanBeEditedAndDeleted() throws Exception {
        String id = extract(send("POST", "/api/media/collections",
                "{\"name\":\"Mine\",\"query\":{\"minHeight\":2160}}").body(), "id");

        HttpResponse<String> renamed = send("PUT", "/api/media/collections/" + id,
                "{\"name\":\"Renamed\",\"pinned\":true}");
        assertTrue(renamed.body().contains("Renamed"), renamed.body());
        assertTrue(renamed.body().contains("\"pinned\":true"), renamed.body());
        // An omitted field is left alone, so a pin toggle need not resend the query.
        assertTrue(renamed.body().contains("\"minHeight\":2160"), renamed.body());

        assertEquals(204, send("DELETE", "/api/media/collections/" + id, null).statusCode());
        assertEquals(404, send("GET", "/api/media/collections/" + id, null).statusCode());
    }

    /** Collections belong to an account, and one account must not see another's. */
    @Test
    void collectionsAreScopedToTheAccount() throws Exception {
        String id = extract(send("POST", "/api/media/collections",
                "{\"name\":\"Mine\",\"query\":{\"minHeight\":2160}}").body(), "id");

        String otherUnique = UUID.randomUUID().toString().substring(0, 8);
        String otherToken = extract(send("POST", "/api/auth/register", """
                {"username":"other_%s","email":"other_%s@example.com","password":"pw123456",
                 "displayName":"Someone Else"}
                """.formatted(otherUnique, otherUnique), null, null).body(), "token");
        // With a profile of their own: listing resolves an active profile, and an account
        // that has none is a different failure from one that may not see this row.
        String otherProfile = extract(send("POST", "/api/profiles",
                "{\"name\":\"Theirs\",\"ageMode\":\"OLDER\"}", otherToken, null).body(), "id");

        assertEquals(404, send("GET", "/api/media/collections/" + id, null, otherToken,
                otherProfile).statusCode());
        assertEquals(404, send("DELETE", "/api/media/collections/" + id, null, otherToken,
                otherProfile).statusCode());
    }

    @Test
    void collectionsRequireAuthentication() throws Exception {
        assertEquals(403, send("GET", "/api/media/collections", null, null, null).statusCode());
    }

    // --- discovered ---

    /**
     * The half nobody has to create. Three titles is the floor — two is a coincidence,
     * not a collection.
     */
    @Test
    void groupsTheLibraryAlreadyContainsBecomeCollections() throws Exception {
        for (int i = 0; i < 3; i++) {
            film("Action" + i, 8.0, 120, 1080, Set.of("Action"), "ta");
        }
        film("LonelyComedy", 8.0, 120, 1080, Set.of("Comedy"), "en");

        String body = send("GET", "/api/media/collections", null).body();

        assertTrue(body.contains("discovered:genre:action"), body);
        assertTrue(body.contains("discovered:language:ta"), body);
        assertTrue(body.contains("Tamil"), body);
        // One comedy is not a collection.
        assertFalse(body.contains("discovered:genre:comedy"), body);
    }

    @Test
    void aDiscoveredCollectionOpensLikeAnyOther() throws Exception {
        for (int i = 0; i < 3; i++) {
            film("Action" + i, 8.0, 120, 1080, Set.of("Action"), "ta");
        }
        film("Drama", 8.0, 120, 1080, Set.of("Drama"), "en");

        String body = send("GET",
                "/api/media/collections/discovered:genre:action/items", null).body();

        assertTrue(body.contains("Action0"), body);
        assertFalse(body.contains("\"title\":\"Drama\""), body);
    }

    /** It is the library describing itself; there is nothing to edit or delete. */
    @Test
    void aDiscoveredCollectionIsReadOnly() throws Exception {
        for (int i = 0; i < 3; i++) {
            film("Action" + i, 8.0, 120, 1080, Set.of("Action"), "ta");
        }

        assertEquals(400, send("PUT", "/api/media/collections/discovered:genre:action",
                "{\"name\":\"Mine now\"}").statusCode());
        assertEquals(400, send("DELETE", "/api/media/collections/discovered:genre:action",
                null).statusCode());
    }

    @Test
    void anUnknownCollectionIsANotFound() throws Exception {
        assertEquals(404, send("GET", "/api/media/collections/builtin:nonsense", null)
                .statusCode());
        assertEquals(404, send("GET", "/api/media/collections/discovered:genre:nonsense", null)
                .statusCode());
        assertEquals(404, send("GET", "/api/media/collections/" + UUID.randomUUID(), null)
                .statusCode());
    }

    // --- the home screen ---

    /** Pinning is the point of pinning: the collection gets a rail of its own. */
    @Test
    void aPinnedCollectionBecomesAHomeRail() throws Exception {
        film("Short4K", 9.0, 100, 2160, null, null);
        film("Long1080", 6.0, 200, 1080, null, null);

        assertFalse(send("GET", "/api/media/home", null).body().contains("collection:"),
                "nothing is pinned yet");

        send("PUT", "/api/media/collections/builtin:four-k", "{\"pinned\":true}");

        String home = send("GET", "/api/media/home", null).body();
        assertTrue(home.contains("\"key\":\"collection:builtin:four-k\""), home);
        assertTrue(home.contains("\"rankedBy\":\"collection\""), home);

        // The rail holds what the collection holds, not what the library holds.
        String rail = home.substring(home.indexOf("collection:builtin:four-k"));
        rail = rail.substring(0, rail.indexOf("\"key\":\"", 1) < 0
                ? rail.length() : rail.indexOf("\"key\":\"", 1));
        assertTrue(rail.contains("Short4K"), rail);
        assertFalse(rail.contains("Long1080"), rail);
    }

    /** A heading over nothing is worse than a missing row. */
    @Test
    void aPinnedCollectionWithNoMembersGetsNoRail() throws Exception {
        film("Long1080", 6.0, 200, 1080, null, null);
        send("PUT", "/api/media/collections/builtin:four-k", "{\"pinned\":true}");

        String home = send("GET", "/api/media/home", null).body();
        assertFalse(home.contains("collection:builtin:four-k"), home);
    }

    /** An owner's own collection earns a rail the same way a built-in does. */
    @Test
    void aPinnedCustomCollectionBecomesAHomeRail() throws Exception {
        film("Wanted", 9.0, 100, 2160, Set.of("Action"), "ta");

        String id = extract(send("POST", "/api/media/collections",
                "{\"name\":\"Tamil action\",\"pinned\":true,"
                        + "\"query\":{\"genres\":[\"action\"],\"languages\":[\"ta\"]}}").body(),
                "id");

        String home = send("GET", "/api/media/home", null).body();
        assertTrue(home.contains("\"key\":\"collection:" + id + "\""), home);
        assertTrue(home.contains("\"title\":\"Tamil action\""), home);
    }

    // --- helpers ---

    private static String assertThrowsMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected a failure");
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
