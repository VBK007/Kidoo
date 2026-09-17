package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.kido.media.assistant.AssistantService;
import com.example.kido.media.assistant.AssistantTools;
import com.example.kido.media.assistant.AssistantTools.Caller;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.playback.PlaybackProgress;
import com.example.kido.media.playback.PlaybackProgressRepository;
import com.example.kido.profile.Profile;
import com.example.kido.profile.ProfileRepository;
import com.example.kido.user.AppUser;
import com.example.kido.user.UserRepository;

/**
 * The assistant's tools, called directly.
 *
 * <p>The tools are the half of the assistant that can be tested for correctness: a model
 * choosing between them is a question about a model, but what each one answers — and
 * what it refuses to answer about — is this code. The three questions in the brief are
 * here as tool calls, because if they cannot be answered by a tool then no amount of
 * prompting will produce them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssistantToolsTest {

    @LocalServerPort
    int port;

    @Autowired
    AssistantTools tools;

    @Autowired
    MediaItemRepository items;

    @Autowired
    PlaybackProgressRepository progress;

    @Autowired
    ProfileRepository profiles;

    @Autowired
    UserRepository users;

    /** Absent unless app.media.ai.assistant-enabled is on, which it is not here. */
    @Autowired
    ObjectProvider<AssistantService> assistant;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private Caller caller;
    private Profile housemate;

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
        progress.deleteAll();
        items.deleteAll();

        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"asst_%s","email":"asst_%s@example.com","password":"pw123456",
                 "displayName":"Assistant Tester"}
                """.formatted(unique, unique), null, null);
        token = extract(registered.body(), "token");
        AppUser owner = users.findByUsername(extract(registered.body(), "username"))
                .orElseGet(() -> users.findAll().stream()
                        .filter(u -> u.getEmail().startsWith("asst_"))
                        .reduce((first, second) -> second).orElseThrow());

        Profile mine = newProfile("Me");
        housemate = newProfile("Housemate");
        caller = new Caller(owner, mine);
    }

    private Profile newProfile(String name) throws Exception {
        HttpResponse<String> created = send("POST", "/api/profiles",
                "{\"name\":\"" + name + "\",\"ageMode\":\"OLDER\"}", token, null);
        return profiles.findById(extract(created.body(), "id")).orElseThrow();
    }

    private MediaItem film(String title, int runtime, double rating, Set<String> genres,
                           String language, long plays) {
        MediaItem item = MediaItem.builder()
                .type(MediaType.FILM)
                .filePath("D:/Media/" + title.replace(' ', '.') + ".mkv")
                .fileName(title.replace(' ', '.') + ".mkv")
                .title(title)
                .sortTitle(title.toLowerCase())
                .year(2019)
                .runtimeMinutes(runtime)
                .rating(rating)
                .primaryLanguage(language)
                .directPlayCount(plays)
                .build();
        if (genres != null) {
            item.getGenres().addAll(genres);
        }
        if (language != null) {
            item.getLanguages().add(language);
        }
        return items.save(item);
    }

    private void finish(MediaItem item, Profile who) {
        progress.save(PlaybackProgress.builder()
                .profileId(who.getId())
                .mediaItemId(item.getId())
                .positionSeconds(7100)
                .durationSeconds(7200.0)
                .watched(true)
                .updatedAt(Instant.now())
                .build());
    }

    // --- the three questions from the brief ---

    /** "Find something I can finish tonight." */
    @Test
    void somethingICanFinishTonight() {
        film("Short", 95, 8.0, Set.of("Action"), "ta", 0);
        film("Epic", 200, 8.0, Set.of("Action"), "ta", 0);

        String answer = tools.searchLibrary(
                Map.of("max_runtime_minutes", 120, "watched", "NOT_ME"), caller);

        assertTrue(answer.contains("Short"), answer);
        assertFalse(answer.contains("Epic"), answer);
        assertTrue(answer.contains("1 title(s) match"), answer);
    }

    /** "What have we watched more than twice?" */
    @Test
    void whatHaveWeWatchedMoreThanTwice() {
        film("Rewatched", 120, 7.0, null, "en", 5);
        film("Once", 120, 7.0, null, "en", 1);

        String answer = tools.mostPlayed(Map.of("min_plays", 3), caller);

        assertTrue(answer.contains("Rewatched"), answer);
        assertTrue(answer.contains("played 5 time(s)"), answer);
        assertFalse(answer.contains("Once"), answer);
    }

    /** "Show movies nobody in the family has watched." */
    @Test
    void whatNobodyInTheFamilyHasWatched() {
        MediaItem theirs = film("SeenByHousemate", 120, 8.0, null, "en", 1);
        film("SeenByNobody", 120, 8.0, null, "en", 0);
        finish(theirs, housemate);

        String answer = tools.searchLibrary(Map.of("watched", "NOBODY"), caller);

        assertTrue(answer.contains("SeenByNobody"), answer);
        assertFalse(answer.contains("SeenByHousemate"),
                "a housemate finishing it means the household has seen it: " + answer);
    }

    // --- who is asking is not a parameter ---

    /**
     * The containment. "I haven't seen it" resolves against whoever is holding the
     * phone, and there is no argument a model could fill in to ask about anyone else.
     */
    @Test
    void watchStateResolvesAgainstTheCallerNotAnArgument() {
        MediaItem mine = film("SeenByMe", 120, 8.0, null, "en", 1);
        MediaItem theirs = film("SeenByThem", 120, 8.0, null, "en", 1);
        finish(mine, caller.profile());
        finish(theirs, housemate);

        String asMe = tools.searchLibrary(Map.of("watched", "NOT_ME"), caller);
        assertTrue(asMe.contains("SeenByThem"), asMe);
        assertFalse(asMe.contains("SeenByMe"), asMe);

        // The same arguments, a different caller, a different answer.
        String asThem = tools.searchLibrary(Map.of("watched", "NOT_ME"),
                new Caller(caller.owner(), housemate));
        assertTrue(asThem.contains("SeenByMe"), asThem);
        assertFalse(asThem.contains("SeenByThem"), asThem);
    }

    @Test
    void tasteIsTheCallersOwn() {
        MediaItem watched = film("Kaithi", 120, 8.0, Set.of("Action"), "ta", 1);
        finish(watched, caller.profile());

        assertTrue(tools.tasteProfile(Map.of(), caller).contains("action"),
                tools.tasteProfile(Map.of(), caller));
        assertTrue(tools.tasteProfile(Map.of(), new Caller(caller.owner(), housemate))
                .contains("no viewing history"));
    }

    // --- arguments come from a model, so nothing may be assumed about them ---

    @Test
    void missingArgumentsAreNoFilterRatherThanAnError() {
        film("Anything", 120, 8.0, null, "en", 0);

        String answer = tools.searchLibrary(Map.of(), caller);

        assertTrue(answer.contains("Anything"), answer);
    }

    @Test
    void wronglyTypedArgumentsAreToleratedNotFatal() {
        film("Short", 95, 8.0, null, "en", 0);
        film("Epic", 200, 8.0, null, "en", 0);

        // A number sent as a string, which is an ordinary thing for a model to do.
        String answer = tools.searchLibrary(Map.of("max_runtime_minutes", "120"), caller);

        assertTrue(answer.contains("Short"), answer);
        assertFalse(answer.contains("Epic"), answer);
    }

    @Test
    void nonsenseArgumentsAreIgnoredRatherThanRejected() {
        film("Anything", 120, 8.0, null, "en", 0);

        String answer = tools.searchLibrary(
                Map.of("watched", "SOMETIMES", "sort", "by vibes", "types", List.of("TVSHOW")),
                caller);

        assertTrue(answer.contains("Anything"),
                "an unusable argument is dropped, not turned into an empty result: " + answer);
    }

    /** A tool result is context paid for on every later turn, so it stays bounded. */
    @Test
    void resultsAreCappedHoweverManyAreAskedFor() {
        for (int i = 0; i < 40; i++) {
            film("Film" + i, 120, 8.0, null, "en", 0);
        }

        String answer = tools.searchLibrary(Map.of("limit", 500), caller);

        assertEquals(25, answer.lines().filter(line -> line.startsWith("- ")).count(), answer);
        // But the true total is still reported, so an answer can say how many there are.
        assertTrue(answer.contains("40 title(s) match"), answer);
    }

    // --- off by default ---

    @Test
    void theAssistantIsNotConstructedUnlessItIsTurnedOn() throws Exception {
        assertEquals(null, assistant.getIfAvailable(),
                "no assistant bean with app.media.ai.assistant-enabled unset");

        HttpResponse<String> availability = send("GET", "/api/media/assistant", null,
                token, caller.profile().getId());
        assertEquals(200, availability.statusCode(), availability.body());
        assertTrue(availability.body().contains("\"available\":false"), availability.body());
    }

    /** Asking with it off is an answer a person can read, not a failed request. */
    @Test
    void askingWithItOffIsAnsweredPolitely() throws Exception {
        HttpResponse<String> response = send("POST", "/api/media/assistant",
                "{\"question\":\"what should I watch\"}", token, caller.profile().getId());

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"answered\":false"), response.body());
        assertTrue(response.body().contains("switched off"), response.body());
    }

    @Test
    void theAssistantRequiresAuthentication() throws Exception {
        assertEquals(403, send("POST", "/api/media/assistant",
                "{\"question\":\"hello\"}", null, null).statusCode());
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
