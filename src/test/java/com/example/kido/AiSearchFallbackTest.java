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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.search.SearchVocabulary;
import com.example.kido.media.search.ai.QueryTranslator;

/**
 * The model fallback, without a model.
 *
 * <p>A stub stands in for the translator, which is the whole reason
 * {@link QueryTranslator} is an interface: what needs proving is the wiring — that the
 * model is asked only when the grammar read nothing, that its answer goes through the
 * same validation as everything else, and that no failure of it can make search worse
 * than it was. None of that needs a network call, and a test that made one would be
 * slow, costly and unreliable about the one thing it was for.
 *
 * <p>What is deliberately not tested here is whether Claude reads English well. That is
 * a question about a model, not about this code, and it is answered by using it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiSearchFallbackTest {

    /** Records what it was asked and answers with whatever the test set. */
    static class StubTranslator implements QueryTranslator {

        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> lastText = new AtomicReference<>();
        final AtomicReference<SearchVocabulary> lastVocabulary = new AtomicReference<>();

        volatile boolean available = true;
        volatile Optional<CatalogQuery> answer = Optional.empty();
        volatile RuntimeException blowUp;

        @Override
        public Optional<CatalogQuery> translate(String text, SearchVocabulary vocabulary) {
            calls.incrementAndGet();
            lastText.set(text);
            lastVocabulary.set(vocabulary);
            if (blowUp != null) {
                throw blowUp;
            }
            return answer;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        void reset() {
            calls.set(0);
            lastText.set(null);
            available = true;
            answer = Optional.empty();
            blowUp = null;
        }
    }

    @TestConfiguration
    static class Stubbed {
        @Bean
        QueryTranslator queryTranslator() {
            return new StubTranslator();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    @Autowired
    QueryTranslator translator;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private String profileId;

    private StubTranslator stub() {
        return (StubTranslator) translator;
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

    private String search(String sentence) throws Exception {
        return send("GET", "/api/media/search?q="
                + URLEncoder.encode(sentence, StandardCharsets.UTF_8), null, token,
                profileId).body();
    }

    @BeforeEach
    void setUp() throws Exception {
        stub().reset();
        items.deleteAll();

        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"ai_%s","email":"ai_%s@example.com","password":"pw123456",
                 "displayName":"AI Tester"}
                """.formatted(unique, unique), null, null);
        token = extract(registered.body(), "token");

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"Tester\",\"ageMode\":\"OLDER\"}", token, null);
        profileId = extract(profile.body(), "id");

        film("Kaithi", Set.of("Action"), "ta");
        film("Old Comedy", Set.of("Comedy"), "en");
    }

    private MediaItem film(String title, Set<String> genres, String language) {
        MediaItem item = MediaItem.builder()
                .type(MediaType.FILM)
                .filePath("D:/Media/" + title.replace(' ', '.') + ".mkv")
                .fileName(title.replace(' ', '.') + ".mkv")
                .title(title)
                .sortTitle(title.toLowerCase())
                .year(2019)
                .runtimeMinutes(120)
                .rating(8.0)
                .primaryLanguage(language)
                .build();
        item.getGenres().addAll(genres);
        item.getLanguages().add(language);
        return items.save(item);
    }

    // --- when the model is asked, and when it is not ---

    /**
     * The common path stays free and instant. A sentence the rules read is never sent
     * anywhere — no latency, no cost, no dependency on somebody else's service for a
     * search that already worked.
     */
    @Test
    void aSentenceTheRulesUnderstandNeverReachesTheModel() throws Exception {
        String body = search("action films");

        assertEquals(0, stub().calls.get(), "the rules handled it");
        assertTrue(body.contains("\"interpretedBy\":\"rules\""), body);
        assertTrue(body.contains("Kaithi"), body);
    }

    /** And nor does a live-chips preview, which fires on every keystroke. */
    @Test
    void theInterpretPreviewNeverReachesTheModel() throws Exception {
        send("GET", "/api/media/search/interpret?q="
                + URLEncoder.encode("something incomprehensible", StandardCharsets.UTF_8),
                null, token, profileId);

        assertEquals(0, stub().calls.get());
    }

    @Test
    void aSentenceTheRulesCannotReadIsHandedOver() throws Exception {
        stub().answer = Optional.of(CatalogQuery.builder()
                .genres(Set.of("comedy"))
                .build());

        String body = search("");

        assertEquals(1, stub().calls.get());
        assertTrue(body.contains("\"interpretedBy\":\"model\""), body);
        assertTrue(body.contains("Old Comedy"), body);
        assertFalse(body.contains("\"title\":\"Kaithi\""), body);
    }

    /** The model is given the library's vocabulary, so it cannot name a genre nobody has. */
    @Test
    void theModelIsToldWhatThisLibraryHolds() throws Exception {
        search("");

        SearchVocabulary given = stub().lastVocabulary.get();
        assertTrue(given.genres().contains("action"), given.genres().toString());
        assertTrue(given.genres().contains("comedy"), given.genres().toString());
        assertTrue(given.languages().containsValue("ta"), given.languages().toString());
    }

    // --- nothing it does can make search worse ---

    @Test
    void anUnavailableTranslatorLeavesTheRulesResultStanding() throws Exception {
        stub().available = false;

        String body = search("");

        assertEquals(0, stub().calls.get(), "an unavailable translator is not called");
        assertTrue(body.contains("\"interpretedBy\":\"rules\""), body);
        // The empty parse is the whole library, which is what it was before.
        assertTrue(body.contains("\"totalItems\":2"), body);
    }

    /** A timeout, an outage, a refusal — all the same thing from here. */
    @Test
    void aTranslatorThatThrowsIsADowngradeNotAFailure() throws Exception {
        stub().blowUp = new IllegalStateException("the network is on fire");

        HttpResponse<String> response = send("GET", "/api/media/search?q=", null, token,
                profileId);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"interpretedBy\":\"rules\""), response.body());
        assertTrue(response.body().contains("\"totalItems\":2"), response.body());
    }

    @Test
    void aTranslatorThatDeclinesLeavesTheRulesResultStanding() throws Exception {
        stub().answer = Optional.empty();

        String body = search("");

        assertEquals(1, stub().calls.get());
        assertTrue(body.contains("\"interpretedBy\":\"rules\""), body);
    }

    // --- the model's answer is not trusted more than anyone else's ---

    /**
     * The containment the whole design rests on: the model produces a query object, and
     * that object goes through exactly the validation a typed URL does.
     */
    @Test
    void theModelsQueryIsValidatedLikeAnyOther() throws Exception {
        // Facets arrive however the model wrote them and are normalised on the way in,
        // the same as a query from a client.
        stub().answer = Optional.of(CatalogQuery.builder()
                .genres(Set.of("  COMEDY  "))
                .build());

        String body = search("");

        assertTrue(body.contains("Old Comedy"), body);
        assertTrue(body.contains("\"genres\":[\"comedy\"]"), body);
    }

    /**
     * A model answer the catalog would refuse falls back rather than failing.
     *
     * <p>Not a 400: the person typed a sentence, and telling them their year range is
     * impossible would be blaming them for something a model did.
     */
    @Test
    void anImpossibleQueryFromTheModelFallsBackRatherThanFailing() throws Exception {
        stub().answer = Optional.of(CatalogQuery.builder()
                .year(new CatalogQuery.Range(2020.0, 2010.0))
                .build());

        HttpResponse<String> response = send("GET", "/api/media/search?q=", null, token,
                profileId);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"interpretedBy\":\"rules\""), response.body());
        assertTrue(response.body().contains("\"totalItems\":2"), response.body());
    }

    /** Chips describe a span-by-span reading, which a whole-query answer does not have. */
    @Test
    void aModelAnswerCarriesNoChips() throws Exception {
        stub().answer = Optional.of(CatalogQuery.builder().genres(Set.of("comedy")).build());

        String body = search("");

        assertTrue(body.contains("\"terms\":[]"), body);
        assertTrue(body.contains("\"interpretedBy\":\"model\""), body);
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
