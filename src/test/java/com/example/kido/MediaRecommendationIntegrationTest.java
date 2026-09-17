package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.engagement.MediaItemLikeRepository;
import com.example.kido.media.playback.PlaybackProgress;
import com.example.kido.media.playback.PlaybackProgressRepository;
import com.example.kido.media.recommend.TasteProfile;
import com.example.kido.media.recommend.TasteProfile.Facet;
import com.example.kido.media.recommend.TasteProfile.FacetKind;
import com.example.kido.media.recommend.TasteProfiler;
import com.example.kido.media.session.WatchEvent;
import com.example.kido.media.session.WatchEventRepository;
import com.example.kido.profile.Profile;
import com.example.kido.profile.ProfileRepository;

/**
 * Recommendations against a real database: what the taste model reads, and what the
 * endpoint does with it.
 *
 * <p>History is written directly rather than played, because what is under test is the
 * derivation and its wiring — and a test that reported its way to three hours of viewing
 * would have to take three hours, the increment being bounded against wall-clock time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaRecommendationIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    @Autowired
    PlaybackProgressRepository progress;

    @Autowired
    WatchEventRepository watchEvents;

    @Autowired
    MediaItemLikeRepository likes;

    @Autowired
    ProfileRepository profiles;

    @Autowired
    TasteProfiler profiler;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private Profile profile;

    private HttpResponse<String> send(String method, String path, String json,
                                      String bearer, String profileId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path));
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
        progress.deleteAll();
        items.deleteAll();

        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"rec_%s","email":"rec_%s@example.com","password":"pw123456",
                 "displayName":"Recommendation Tester"}
                """.formatted(unique, unique), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        token = extract(registered.body(), "token");

        HttpResponse<String> created = send("POST", "/api/profiles",
                "{\"name\":\"Tester\",\"ageMode\":\"OLDER\"}", token, null);
        assertEquals(201, created.statusCode(), created.body());
        profile = profiles.findById(extract(created.body(), "id")).orElseThrow();
    }

    private MediaItem film(String title, Double rating, Set<String> genres, String language) {
        MediaItem item = MediaItem.builder()
                .type(MediaType.FILM)
                .filePath("D:/Media/" + title.replace(' ', '.') + ".mkv")
                .fileName(title.replace(' ', '.') + ".mkv")
                .title(title)
                .sortTitle(title.toLowerCase())
                .year(2019)
                .runtimeMinutes(120)
                .rating(rating)
                .primaryLanguage(language)
                .build();
        if (genres != null) {
            item.getGenres().addAll(genres);
        }
        if (language != null) {
            item.getLanguages().add(language);
        }
        return items.save(item);
    }

    /** Marks a title finished, the way the player's last progress report would. */
    private void finish(MediaItem item) {
        progress.save(PlaybackProgress.builder()
                .profileId(profile.getId())
                .mediaItemId(item.getId())
                .positionSeconds(7100)
                .durationSeconds(7200.0)
                .watched(true)
                .updatedAt(Instant.now())
                .build());
    }

    /** Started, barely watched, and not touched since — a person saying no. */
    private void abandon(MediaItem item) {
        progress.save(PlaybackProgress.builder()
                .profileId(profile.getId())
                .mediaItemId(item.getId())
                .positionSeconds(300)
                .durationSeconds(7200.0)
                .watched(false)
                .updatedAt(Instant.now().minus(60, ChronoUnit.DAYS))
                .build());
        watchEvents.save(WatchEvent.builder()
                .profileId(profile.getId())
                .mediaItemId(item.getId())
                .secondsWatched(300)
                .build());
    }

    // --- the taste model ---

    @Test
    void finishingSomethingEstablishesATaste() {
        MediaItem watched = film("Kaithi", 8.5, Set.of("Action"), "ta");
        finish(watched);

        TasteProfile taste = profiler.forProfile(profile);

        assertEquals(1, taste.signalCount());
        assertTrue(taste.weightOf(new Facet(FacetKind.GENRE, "action")) > 0);
        assertTrue(taste.weightOf(new Facet(FacetKind.LANGUAGE, "ta")) > 0);
        // The evidence is what makes a recommendation checkable rather than a horoscope.
        assertEquals("Kaithi",
                taste.evidenceFor(new Facet(FacetKind.GENRE, "action")).orElseThrow());
    }

    /**
     * The signal nothing in this codebase was reading. Without it a recommender keeps
     * pushing the thing somebody already rejected.
     */
    @Test
    void walkingAwayFromSomethingIsANegativeSignal() {
        MediaItem liked = film("Kaithi", 8.5, Set.of("Action"), "ta");
        MediaItem dropped = film("Dull", 8.5, Set.of("Documentary"), "ta");
        finish(liked);
        abandon(dropped);

        TasteProfile taste = profiler.forProfile(profile);

        assertTrue(taste.weightOf(new Facet(FacetKind.GENRE, "action")) > 0);
        assertTrue(taste.weightOf(new Facet(FacetKind.GENRE, "documentary")) < 0,
                "a title walked away from should push its facets down, not merely not up");
    }

    /** Barely started but touched recently is "I'll finish it later", not a rejection. */
    @Test
    void somethingStartedYesterdayIsNotYetAVerdict() {
        MediaItem started = film("JustStarted", 8.5, Set.of("Documentary"), "ta");
        progress.save(PlaybackProgress.builder()
                .profileId(profile.getId())
                .mediaItemId(started.getId())
                .positionSeconds(300)
                .durationSeconds(7200.0)
                .watched(false)
                .updatedAt(Instant.now())
                .build());

        TasteProfile taste = profiler.forProfile(profile);

        assertEquals(0.0, taste.weightOf(new Facet(FacetKind.GENRE, "documentary")));
    }

    /** One housemate's viewing must not become another's taste. */
    @Test
    void tasteIsPerProfile() throws Exception {
        MediaItem watched = film("Kaithi", 8.5, Set.of("Action"), "ta");
        finish(watched);

        HttpResponse<String> created = send("POST", "/api/profiles",
                "{\"name\":\"Housemate\",\"ageMode\":\"OLDER\"}", token, null);
        Profile other = profiles.findById(extract(created.body(), "id")).orElseThrow();

        assertTrue(profiler.forProfile(other).isEmpty());
        assertFalse(profiler.forProfile(profile).isEmpty());
    }

    // --- the endpoint ---

    /** The whole point: what this person watches beats what the library rates highest. */
    @Test
    void picksFollowWhatThisProfileActuallyWatches() throws Exception {
        finish(film("Kaithi", 8.0, Set.of("Action"), "ta"));
        film("MoreAction", 7.5, Set.of("Action"), "ta");
        film("AcclaimedOpera", 9.5, Set.of("Opera"), "de");

        String body = send("GET", "/api/media/recommendations", null, token,
                profile.getId()).body();

        assertEquals("MoreAction", firstTitle(body), body);
        assertTrue(body.contains("Because you watched Kaithi"), body);
        assertTrue(body.contains("\"coldStart\":false"), body);
    }

    /** Already finished is not a recommendation; that would be a different feature. */
    @Test
    void somethingAlreadyFinishedIsNotRecommended() throws Exception {
        MediaItem done = film("Finished", 9.0, Set.of("Action"), "ta");
        finish(done);
        film("Unseen", 7.0, Set.of("Action"), "ta");

        String body = send("GET", "/api/media/recommendations", null, token,
                profile.getId()).body();

        assertTrue(body.contains("Unseen"), body);
        assertFalse(body.contains("\"title\":\"Finished\""), body);
    }

    /** Half-watched belongs on continue-watching; in both places the screen looks thinner. */
    @Test
    void somethingInFlightIsLeftToTheContinueWatchingRow() throws Exception {
        MediaItem inFlight = film("HalfWatched", 9.0, Set.of("Action"), "ta");
        progress.save(PlaybackProgress.builder()
                .profileId(profile.getId())
                .mediaItemId(inFlight.getId())
                .positionSeconds(3000)
                .durationSeconds(7200.0)
                .watched(false)
                .updatedAt(Instant.now())
                .build());
        film("Untouched", 7.0, Set.of("Action"), "ta");

        String body = send("GET", "/api/media/recommendations", null, token,
                profile.getId()).body();

        assertTrue(body.contains("Untouched"), body);
        assertFalse(body.contains("\"title\":\"HalfWatched\""), body);
    }

    /** A new profile gets a sensible screen, not an empty one. */
    @Test
    void aProfileWithNoHistoryStillGetsPicks() throws Exception {
        film("Acclaimed", 9.2, Set.of("Action"), "ta");
        film("Mediocre", 4.0, Set.of("Action"), "ta");

        String body = send("GET", "/api/media/recommendations", null, token,
                profile.getId()).body();

        assertTrue(body.contains("\"coldStart\":true"), body);
        assertTrue(body.contains("\"basedOn\":0"), body);
        assertEquals("Acclaimed", firstTitle(body), body);
    }

    /** The client can show what the server thinks it knows, so a person can see it is wrong. */
    @Test
    void theResponseSaysWhatItBelievesAboutYou() throws Exception {
        finish(film("Kaithi", 8.0, Set.of("Action"), "ta"));
        film("Another", 7.0, Set.of("Action"), "ta");

        String body = send("GET", "/api/media/recommendations", null, token,
                profile.getId()).body();

        assertTrue(body.contains("\"taste\":["), body);
        assertTrue(body.contains("\"kind\":\"genre\""), body);
        assertTrue(body.contains("\"value\":\"action\""), body);
        assertTrue(body.contains("\"evidence\":\"Kaithi\""), body);
    }

    @Test
    void anEmptyLibraryReturnsNoPicksRatherThanFailing() throws Exception {
        HttpResponse<String> response = send("GET", "/api/media/recommendations", null,
                token, profile.getId());

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"items\":[]"), response.body());
    }

    @Test
    void recommendationsRequireAuthentication() throws Exception {
        assertEquals(403, send("GET", "/api/media/recommendations", null, null, null)
                .statusCode());
    }

    // --- the home screen ---

    @Test
    void picksLeadTheHomeScreenWhenThereAreAny() throws Exception {
        finish(film("Kaithi", 8.0, Set.of("Action"), "ta"));
        film("MoreAction", 7.5, Set.of("Action"), "ta");

        String body = send("GET", "/api/media/home", null, token, profile.getId()).body();

        assertTrue(body.contains("\"key\":\"for-you\""), body);
        assertTrue(body.contains("Tonight's picks"), body);
        assertTrue(body.indexOf("\"key\":\"for-you\"") < body.indexOf("\"key\":\"popular\""),
                "the for-you rail leads");
    }

    /** With nothing unwatched left, a "for you" heading over nothing is worse than no rail. */
    @Test
    void theHomeRailIsOmittedWhenThereIsNothingToSuggest() throws Exception {
        MediaItem only = film("Seen", 8.0, Set.of("Action"), "ta");
        finish(only);

        String body = send("GET", "/api/media/home", null, token, profile.getId()).body();

        assertFalse(body.contains("\"key\":\"for-you\""), body);
    }

    // --- helpers ---

    /** The first item's title, anchored on the year that follows it in a tile. */
    private static String firstTitle(String json) {
        Matcher matcher = Pattern
                .compile("\"title\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"year\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
