package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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

import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.playback.PlaybackProgress;
import com.example.kido.media.playback.PlaybackProgressRepository;
import com.example.kido.profile.Profile;
import com.example.kido.media.search.ai.QueryTranslator;
import com.example.kido.profile.ProfileRepository;

/**
 * Search by sentence, against a real library.
 *
 * <p>The parser has its own tests; what is under test here is the half that needs a
 * database — that the vocabulary is the library's own, and that a parsed sentence
 * actually selects the right films rather than merely producing a plausible-looking
 * query object.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaSearchIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    @Autowired
    PlaybackProgressRepository progress;

    @Autowired
    ProfileRepository profiles;

    /** Empty unless app.media.ai.enabled is on, which it is not in this suite. */
    @Autowired
    ObjectProvider<QueryTranslator> translators;

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

    /** The sentence, as a client would send it. */
    private String search(String sentence) throws Exception {
        return send("GET", "/api/media/search?q="
                + URLEncoder.encode(sentence, StandardCharsets.UTF_8), null, token,
                profile.getId()).body();
    }

    @BeforeEach
    void setUp() throws Exception {
        progress.deleteAll();
        items.deleteAll();

        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"srch_%s","email":"srch_%s@example.com","password":"pw123456",
                 "displayName":"Search Tester"}
                """.formatted(unique, unique), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        token = extract(registered.body(), "token");

        HttpResponse<String> created = send("POST", "/api/profiles",
                "{\"name\":\"Tester\",\"ageMode\":\"OLDER\"}", token, null);
        profile = profiles.findById(extract(created.body(), "id")).orElseThrow();
    }

    private MediaItem film(String title, int year, int runtime, double rating,
                           Set<String> genres, String language, int height,
                           Set<String> people) {
        MediaItem item = MediaItem.builder()
                .type(MediaType.FILM)
                .filePath("D:/Media/" + title.replace(' ', '.') + ".mkv")
                .fileName(title.replace(' ', '.') + ".mkv")
                .title(title)
                .sortTitle(title.toLowerCase())
                .year(year)
                .runtimeMinutes(runtime)
                .rating(rating)
                .primaryLanguage(language)
                .mediaInfo(MediaInfo.builder().height(height).build())
                .build();
        if (genres != null) {
            item.getGenres().addAll(genres);
        }
        if (people != null) {
            item.getPeople().addAll(people);
        }
        if (language != null) {
            item.getLanguages().add(language);
        }
        return items.save(item);
    }

    /** The library these sentences are asked of. */
    private void seedLibrary() {
        film("Kaithi", 2019, 145, 8.5, Set.of("Action"), "ta", 1080, Set.of("Karthi"));
        film("Vikram", 2022, 174, 8.3, Set.of("Action"), "ta", 2160, Set.of("Kamal Haasan"));
        film("Short Tamil", 2018, 95, 8.9, Set.of("Drama"), "ta", 1080, null);
        film("English Action", 2019, 100, 9.1, Set.of("Action"), "en", 2160, null);
        film("Old Comedy", 1994, 100, 7.0, Set.of("Comedy"), "en", 720, null);
    }

    // --- the sentence this stage was specified against ---

    @Test
    void theHeadlineSentenceFindsTheRightFilms() throws Exception {
        seedLibrary();

        String body = search("Show me Tamil movies under 2 hours with rating > 8");

        assertTrue(body.contains("Short Tamil"), body);
        assertFalse(body.contains("\"title\":\"Kaithi\""), body);       // 145 minutes
        assertFalse(body.contains("\"title\":\"English Action\""), body); // not Tamil
        assertTrue(body.contains("\"totalItems\":1"), body);
    }

    /** The parse comes back so a client can draw it as chips and a person can correct it. */
    @Test
    void theResponseShowsWhatItUnderstood() throws Exception {
        seedLibrary();

        String body = search("Tamil films under 2 hours rated over 8");

        assertTrue(body.contains("\"field\":\"language\""), body);
        assertTrue(body.contains("\"label\":\"Tamil\""), body);
        assertTrue(body.contains("\"label\":\"Under 2 hours\""), body);
        assertTrue(body.contains("\"label\":\"Rated 8+\""), body);
        // The query itself is returned, so the client can edit and re-run it directly.
        assertTrue(body.contains("\"query\":{"), body);
        assertTrue(body.contains("\"understoodNothing\":false"), body);
    }

    // --- the vocabulary is this library's ---

    /** A genre resolves because the library has it, not because the parser knows the word. */
    @Test
    void genresComeFromWhatTheLibraryActuallyHolds() throws Exception {
        seedLibrary();

        String body = search("comedy");

        assertTrue(body.contains("\"field\":\"genre\""), body);
        assertTrue(body.contains("Old Comedy"), body);
    }

    /** A word no film carries cannot become a filter; it falls through to a title search. */
    @Test
    void anUnknownWordBecomesAVisibleTitleSearchRatherThanAGuess() throws Exception {
        seedLibrary();

        String body = search("kaithi");

        assertTrue(body.contains("\"field\":\"title\""), body);
        assertTrue(body.contains("Kaithi"), body);
    }

    @Test
    void castNamesAreSearchableBecauseTheLibraryTaggedThem() throws Exception {
        seedLibrary();

        String body = search("films with kamal haasan");

        assertTrue(body.contains("\"field\":\"person\""), body);
        assertTrue(body.contains("Vikram"), body);
        assertFalse(body.contains("\"title\":\"Kaithi\""), body);
    }

    /**
     * A song is not looked for by its title.
     *
     * <p>Searching a music director's name over a library holding a shelf of his work
     * returned nothing at all, because the free-text match only ever read the title,
     * the sort title and the file name — and his name is in none of the three.
     */
    @Test
    void musicIsFoundByWhoMadeIt() throws Exception {
        track("Ennullea", "Swarnalatha", "Kadhalan", "Ilaiyaraaja");
        track("Unrelated Song", "Somebody Else", "Other Film", "Another Composer");

        assertTrue(search("ilaiyaraaja").contains("Ennullea"));
        assertTrue(search("swarnalatha").contains("Ennullea"));
        assertTrue(search("kadhalan").contains("Ennullea"));
    }

    @Test
    void searchingAMusicNameDoesNotDragInEverythingElse() throws Exception {
        track("Ennullea", "Swarnalatha", "Kadhalan", "Ilaiyaraaja");
        track("Unrelated Song", "Somebody Else", "Other Film", "Another Composer");

        assertFalse(search("ilaiyaraaja").contains("Unrelated Song"));
    }

    private MediaItem track(String title, String artist, String album, String musicDirector) {
        return items.save(MediaItem.builder()
                .type(MediaType.MUSIC)
                .filePath("D:/Music/" + title.replace(' ', '.') + ".mp3")
                .fileName(title.replace(' ', '.') + ".mp3")
                .title(title)
                .sortTitle(title.toLowerCase())
                .artist(artist)
                .album(album)
                .musicDirector(musicDirector)
                .build());
    }

    // --- filters that need the database to prove ---

    @Test
    void decadesSelectTheRightYears() throws Exception {
        seedLibrary();

        String body = search("comedy from the 90s");

        assertTrue(body.contains("Old Comedy"), body);
        assertTrue(body.contains("\"totalItems\":1"), body);
    }

    @Test
    void qualityAndLanguageCombine() throws Exception {
        seedLibrary();

        String body = search("4k tamil films");

        assertTrue(body.contains("Vikram"), body);
        assertFalse(body.contains("\"title\":\"Kaithi\""), body);
        assertFalse(body.contains("English Action"), body);
    }

    /** The one filter that reads per-profile state, so it has to run against real history. */
    @Test
    void watchStateIsAskedOfTheRightPerson() throws Exception {
        seedLibrary();
        MediaItem seen = items.findAll().stream()
                .filter(item -> item.getTitle().equals("Kaithi")).findFirst().orElseThrow();
        progress.save(PlaybackProgress.builder()
                .profileId(profile.getId())
                .mediaItemId(seen.getId())
                .positionSeconds(8600)
                .durationSeconds(8700.0)
                .watched(true)
                .updatedAt(Instant.now())
                .build());

        String unwatched = search("tamil films i haven't seen");
        assertFalse(unwatched.contains("\"title\":\"Kaithi\""), unwatched);
        assertTrue(unwatched.contains("Vikram"), unwatched);

        String nobody = search("tamil films nobody has watched");
        assertFalse(nobody.contains("\"title\":\"Kaithi\""), nobody);
    }

    // --- edges ---

    @Test
    void anEmptySearchReturnsTheLibraryRatherThanFailing() throws Exception {
        seedLibrary();

        HttpResponse<String> response = send("GET", "/api/media/search", null, token,
                profile.getId());

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"understoodNothing\":true"), response.body());
        assertTrue(response.body().contains("\"totalItems\":5"), response.body());
    }

    @Test
    void aSentenceThatMatchesNothingIsAnEmptyPageNotAnError() throws Exception {
        seedLibrary();

        String body = search("tamil films under 10 minutes");

        assertTrue(body.contains("\"items\":[]"), body);
        assertTrue(body.contains("\"totalItems\":0"), body);
    }

    /** The parse alone, for a box that shows chips as somebody types. */
    @Test
    void interpretReturnsTheTermsWithoutRunningTheSearch() throws Exception {
        seedLibrary();

        HttpResponse<String> response = send("GET", "/api/media/search/interpret?q="
                        + URLEncoder.encode("4k action films", StandardCharsets.UTF_8),
                null, token, profile.getId());

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"label\":\"4K\""), response.body());
        assertTrue(response.body().contains("\"label\":\"Action\""), response.body());
        assertFalse(response.body().contains("\"results\""), response.body());
    }

    @Test
    void searchRequiresAuthentication() throws Exception {
        assertEquals(403, send("GET", "/api/media/search?q=action", null, null, null)
                .statusCode());
    }

    /** Paging is the catalog's, so a long result set behaves like every other listing. */
    @Test
    void resultsArePaged() throws Exception {
        for (int i = 0; i < 5; i++) {
            film("Action" + i, 2019, 100, 8.0, Set.of("Action"), "en", 1080, null);
        }

        String body = send("GET", "/api/media/search?q=action&page=0&size=2", null, token,
                profile.getId()).body();

        assertTrue(body.contains("\"totalItems\":5"), body);
        assertTrue(body.contains("\"totalPages\":3"), body);
        assertEquals(2, countOccurrences(body, "\"year\":2019"), body);
    }

    // --- the model is off by default ---

    /**
     * The shipped default. A home media server must search a disk it already has with no
     * account anywhere and no internet, so the translator bean does not even exist unless
     * somebody turns it on.
     */
    @Test
    void searchWorksWithNoModelConfigured() throws Exception {
        seedLibrary();

        String body = search("tamil films");

        assertTrue(body.contains("\"interpretedBy\":\"rules\""), body);
        assertTrue(body.contains("Kaithi"), body);
        assertNull(translators.getIfAvailable(),
                "no translator bean should exist with app.media.ai.enabled unset");
    }

    // --- helpers ---

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
