package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.dto.CatalogDtos.ItemPageDto;
import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;
import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.query.CatalogQuery.MatchMode;
import com.example.kido.media.query.CatalogQuery.Range;
import com.example.kido.media.query.CatalogQuery.WatchedBy;
import com.example.kido.profile.Profile;
import com.example.kido.profile.ProfileRepository;

/**
 * {@link CatalogQuery} against a real database: every filter, and the paging the filters
 * have to survive.
 *
 * <p>Driven through {@code CatalogService.search} rather than over HTTP, because that is
 * the seam the stored collections and the search parser will use — the browse endpoint
 * is one caller of it, covered here only where its own translation is what is under
 * test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaCatalogQueryIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    @Autowired
    ProfileRepository profiles;

    @Autowired
    CatalogService catalog;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private Profile profile;
    private Profile otherProfile;

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
        items.deleteAll();

        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"q_%s","email":"q_%s@example.com","password":"pw123456",
                 "displayName":"Query Tester"}
                """.formatted(unique, unique), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        token = extract(registered.body(), "token");

        profile = newProfile("Watcher");
        otherProfile = newProfile("Housemate");
    }

    private Profile newProfile(String name) throws Exception {
        HttpResponse<String> created = send("POST", "/api/profiles",
                "{\"name\":\"" + name + "\",\"ageMode\":\"OLDER\"}", token, null);
        assertEquals(201, created.statusCode(), created.body());
        return profiles.findById(extract(created.body(), "id")).orElseThrow();
    }

    private MediaItem film(String title, MediaType type, Integer year, Integer runtime,
                           Double rating, Set<String> genres, Integer height) {
        MediaItem item = MediaItem.builder()
                .type(type)
                .filePath("D:/Media/" + title.replace(' ', '.') + ".mkv")
                .fileName(title.replace(' ', '.') + ".mkv")
                .title(title)
                .sortTitle(title.toLowerCase())
                .year(year)
                .runtimeMinutes(runtime)
                .rating(rating)
                .mediaInfo(MediaInfo.builder().height(height).build())
                .build();
        if (genres != null) {
            item.getGenres().addAll(genres);
        }
        return items.save(item);
    }

    private List<String> titlesOf(CatalogQuery query) {
        return titlesOf(query, profile);
    }

    private List<String> titlesOf(CatalogQuery query, Profile as) {
        return catalog.search(as, query, 0, 100).items().stream()
                .map(ItemSummaryDto::title)
                .toList();
    }

    // --- the filters ---

    @Test
    void typeAndTitleFilter() {
        film("Kaithi", MediaType.FILM, 2019, 145, 8.5, null, 1080);
        film("Naruto", MediaType.ANIME, 2002, 24, 8.3, null, 720);

        assertEquals(List.of("Kaithi"),
                titlesOf(CatalogQuery.builder().types(Set.of(MediaType.FILM)).build()));
        assertEquals(List.of("Naruto"),
                titlesOf(CatalogQuery.builder().titleContains("naru").build()));
        // Filename is searched too, which is how a title the scraper never matched is found.
        assertEquals(List.of("Naruto"),
                titlesOf(CatalogQuery.builder().titleContains("Naruto.mkv").build()));
    }

    @Test
    void rangesAreInclusiveAndOpenEnded() {
        film("Short", MediaType.FILM, 2019, 95, 7.0, null, 1080);
        film("Long", MediaType.FILM, 1998, 180, 9.0, null, 1080);

        assertEquals(List.of("Short"),
                titlesOf(CatalogQuery.builder().runtimeMinutes(Range.atMost(120)).build()));
        assertEquals(List.of("Long"),
                titlesOf(CatalogQuery.builder().rating(Range.atLeast(8.5)).build()));
        assertEquals(List.of("Long"),
                titlesOf(CatalogQuery.builder().year(Range.between(1990, 2000)).build()));
        // Inclusive at both ends: a 95-minute film is "under 95 minutes or exactly".
        assertEquals(List.of("Short"),
                titlesOf(CatalogQuery.builder().runtimeMinutes(Range.atMost(95)).build()));
    }

    /**
     * The distinction that makes multi-genre search worth having: "action or comedy" and
     * "action and comedy" are different questions with the same two words.
     */
    @Test
    void genresMatchAnyOrAll() {
        film("Both", MediaType.FILM, 2019, 120, 8.0, Set.of("Action", "Comedy"), 1080);
        film("ActionOnly", MediaType.FILM, 2019, 120, 8.0, Set.of("Action"), 1080);

        assertEquals(List.of("ActionOnly", "Both"), titlesOf(CatalogQuery.builder()
                .genres(Set.of("action", "comedy")).genreMatch(MatchMode.ANY)
                .sort("title").build()));
        assertEquals(List.of("Both"), titlesOf(CatalogQuery.builder()
                .genres(Set.of("action", "comedy")).genreMatch(MatchMode.ALL)
                .sort("title").build()));
    }

    /** Genres are stored as the scraper wrote them; the filter must not care about case. */
    @Test
    void genreMatchingIsCaseInsensitive() {
        film("Cased", MediaType.FILM, 2019, 120, 8.0, Set.of("Sci-Fi"), 1080);
        assertEquals(List.of("Cased"),
                titlesOf(CatalogQuery.builder().genres(Set.of("SCI-FI")).build()));
    }

    @Test
    void languageFilterDistinguishesAnyTrackFromThePrimaryOne() {
        MediaItem dual = film("Dual", MediaType.FILM, 2019, 120, 8.0, null, 1080);
        dual.getLanguages().addAll(List.of("en", "ta"));
        dual.setPrimaryLanguage("en");
        items.save(dual);

        MediaItem tamil = film("TamilFirst", MediaType.FILM, 2019, 120, 8.0, null, 1080);
        tamil.getLanguages().add("ta");
        tamil.setPrimaryLanguage("ta");
        items.save(tamil);

        // "a film with a Tamil track somewhere"
        assertEquals(List.of("Dual", "TamilFirst"), titlesOf(CatalogQuery.builder()
                .languages(Set.of("ta")).sort("title").build()));
        // "a Tamil film"
        assertEquals(List.of("TamilFirst"), titlesOf(CatalogQuery.builder()
                .languages(Set.of("ta")).primaryLanguageOnly(true).sort("title").build()));
    }

    @Test
    void minHeightBacksTheFourKChip() {
        film("UHD", MediaType.FILM, 2019, 120, 8.0, null, 2160);
        film("HD", MediaType.FILM, 2019, 120, 8.0, null, 1080);

        assertEquals(List.of("UHD"),
                titlesOf(CatalogQuery.builder().minHeight(2160).build()));
    }

    @Test
    void filtersCombine() {
        film("Wanted", MediaType.FILM, 2019, 110, 8.6, Set.of("Action"), 2160);
        film("TooLong", MediaType.FILM, 2019, 190, 8.6, Set.of("Action"), 2160);
        film("TooDull", MediaType.FILM, 2019, 110, 6.0, Set.of("Action"), 2160);
        film("TooSmall", MediaType.FILM, 2019, 110, 8.6, Set.of("Action"), 1080);

        assertEquals(List.of("Wanted"), titlesOf(CatalogQuery.builder()
                .types(Set.of(MediaType.FILM))
                .genres(Set.of("action"))
                .runtimeMinutes(Range.atMost(120))
                .rating(Range.atLeast(8))
                .minHeight(2160)
                .build()));
    }

    // --- watch state ---

    /**
     * Per-profile and per-household are different questions, and a Never-watched
     * collection is only worth showing if it means the second one.
     */
    @Test
    void watchStateIsAskedPerProfileOrPerHousehold() throws Exception {
        MediaItem seen = film("SeenByMe", MediaType.FILM, 2019, 120, 8.0, null, 1080);
        MediaItem theirs = film("SeenByThem", MediaType.FILM, 2019, 120, 8.0, null, 1080);
        film("SeenByNobody", MediaType.FILM, 2019, 120, 8.0, null, 1080);

        finish(seen, profile);
        finish(theirs, otherProfile);

        assertEquals(List.of("SeenByMe"),
                titlesOf(CatalogQuery.builder().watched(WatchedBy.ME).sort("title").build()));
        assertEquals(List.of("SeenByNobody", "SeenByThem"),
                titlesOf(CatalogQuery.builder().watched(WatchedBy.NOT_ME).sort("title").build()));
        assertEquals(List.of("SeenByMe", "SeenByThem"),
                titlesOf(CatalogQuery.builder().watched(WatchedBy.SOMEONE).sort("title").build()));
        assertEquals(List.of("SeenByNobody"),
                titlesOf(CatalogQuery.builder().watched(WatchedBy.NOBODY).sort("title").build()));
    }

    /** A like is per profile, so the filter cannot read the household's total. */
    @Test
    void likedFilterIsPerProfile() throws Exception {
        MediaItem liked = film("Liked", MediaType.FILM, 2019, 120, 8.0, null, 1080);
        film("Unliked", MediaType.FILM, 2019, 120, 8.0, null, 1080);

        assertEquals(200, send("PUT", "/api/media/items/" + liked.getId() + "/like",
                null, token, profile.getId()).statusCode());

        assertEquals(List.of("Liked"),
                titlesOf(CatalogQuery.builder().liked(true).sort("title").build()));
        assertEquals(List.of("Unliked"),
                titlesOf(CatalogQuery.builder().liked(false).sort("title").build()));
        // The other profile did not like it, whatever the household count says.
        assertEquals(List.of(), titlesOf(
                CatalogQuery.builder().liked(true).build(), otherProfile));
    }

    // --- paging ---

    /**
     * The bug this stage fixes. The watch filter used to be applied to a page after it
     * came back, so a page was short and the total counted rows the filter removed. A
     * client paging through UNWATCHED saw both.
     */
    @Test
    void watchFilterIsAppliedBeforePagingNotAfter() {
        for (int i = 0; i < 6; i++) {
            MediaItem item = film("Film" + i, MediaType.FILM, 2019, 120, 8.0, null, 1080);
            if (i < 4) {
                finish(item, profile);
            }
        }

        ItemPageDto page = catalog.search(profile,
                CatalogQuery.builder().watched(WatchedBy.NOT_ME).sort("title").build(), 0, 2);

        // Two unwatched films exist, so: a full page of the two, and a total of two.
        assertEquals(2, page.items().size(), "page must be filled from the filtered set");
        assertEquals(2, page.totalItems(), "total must count only what the filter keeps");
        assertEquals(1, page.totalPages());
    }

    /**
     * A facet filter joins a collection table, which multiplies rows unless it is done
     * as a subquery. The visible symptom of getting it wrong is a total larger than the
     * number of items.
     */
    @Test
    void multiValuedFacetsDoNotInflateTheTotal() {
        film("ManyGenres", MediaType.FILM, 2019, 120, 8.0,
                Set.of("Action", "Comedy", "Drama", "Thriller"), 1080);

        ItemPageDto page = catalog.search(profile,
                CatalogQuery.builder().genres(Set.of("action", "comedy")).build(), 0, 40);

        assertEquals(1, page.items().size());
        assertEquals(1, page.totalItems(), "a four-genre film is still one film");
    }

    @Test
    void pagingIsStableAcrossPages() {
        for (int i = 0; i < 5; i++) {
            film("Film" + i, MediaType.FILM, 2019, 120, 8.0, null, 1080);
        }
        CatalogQuery query = CatalogQuery.builder().sort("title").build();

        List<String> first = catalog.search(profile, query, 0, 2).items()
                .stream().map(ItemSummaryDto::title).toList();
        List<String> second = catalog.search(profile, query, 1, 2).items()
                .stream().map(ItemSummaryDto::title).toList();

        assertEquals(List.of("Film0", "Film1"), first);
        assertEquals(List.of("Film2", "Film3"), second);
    }

    // --- the browse endpoint still behaves ---

    /** The public contract must not have moved, however the inside was rearranged. */
    @Test
    void browseEndpointIsUnchanged() throws Exception {
        film("Kaithi", MediaType.FILM, 2019, 145, 8.5, Set.of("Action"), 2160);
        film("Naruto", MediaType.ANIME, 2002, 24, 8.3, null, 720);

        String all = send("GET", "/api/media/items", null, token, profile.getId()).body();
        assertTrue(all.contains("Kaithi") && all.contains("Naruto"), all);

        String films = send("GET", "/api/media/items?category=films", null, token,
                profile.getId()).body();
        assertTrue(films.contains("Kaithi") && !films.contains("Naruto"), films);

        String fourK = send("GET", "/api/media/items?minHeight=2160", null, token,
                profile.getId()).body();
        assertTrue(fourK.contains("Kaithi") && !fourK.contains("Naruto"), fourK);

        String byGenre = send("GET", "/api/media/items?genre=Action", null, token,
                profile.getId()).body();
        assertTrue(byGenre.contains("Kaithi") && !byGenre.contains("Naruto"), byGenre);

        assertEquals(400, send("GET", "/api/media/items?sort=nonsense", null, token,
                profile.getId()).statusCode());
        assertEquals(400, send("GET", "/api/media/items?category=nonsense", null, token,
                profile.getId()).statusCode());
    }

    /** The UNWATCHED chip, over HTTP, with the paging fix behind it. */
    @Test
    void browseEndpointUnwatchedChipFiltersInTheDatabase() throws Exception {
        MediaItem seen = film("Seen", MediaType.FILM, 2019, 120, 8.0, null, 1080);
        film("Unseen", MediaType.FILM, 2019, 120, 8.0, null, 1080);
        finish(seen, profile);

        String body = send("GET", "/api/media/items?unwatched=true", null, token,
                profile.getId()).body();

        assertTrue(body.contains("Unseen"), body);
        assertTrue(!body.contains("\"title\":\"Seen\""), body);
        assertTrue(body.contains("\"totalItems\":1"), body);
    }

    // --- helpers ---

    /** Marks a title finished for a profile, the way the player's last report would. */
    private void finish(MediaItem item, Profile who) {
        try {
            HttpResponse<String> response = send("PUT",
                    "/api/media/items/" + item.getId() + "/progress",
                    "{\"positionSeconds\":7100,\"durationSeconds\":7200}", token, who.getId());
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"watched\":true"), response.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
