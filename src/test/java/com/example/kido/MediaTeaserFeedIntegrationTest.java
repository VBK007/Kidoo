package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
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
import com.example.kido.media.teasers.TeaserClip;
import com.example.kido.media.teasers.TeaserClipRepository;

/**
 * The shorts feed, over real HTTP against in-memory H2.
 *
 * <p>The clips are written straight to the repository rather than cut: generating one
 * means an ffmpeg encode of a film this machine does not have, and what is under test is
 * the order they come back in, not the encoder.
 *
 * <p>The property that matters is an awkward pair — the order has to be random, and it
 * has to be the <em>same</em> random order for every page of one scroll. Either half is
 * easy alone; a test is worth having because the obvious implementation of the first
 * ({@code order by random()}) silently destroys the second.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaTeaserFeedIntegrationTest {

    /** Enough clips that a shuffle landing back on the id order is not worth thinking about. */
    private static final int CLIP_COUNT = 12;

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    @Autowired
    TeaserClipRepository clips;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private String profileId;
    private String itemId;

    @BeforeEach
    void setUp() throws Exception {
        clips.deleteAll();
        items.deleteAll();

        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"short_%s","email":"short_%s@example.com","password":"pw123456",
                 "displayName":"Shorts Tester"}
                """.formatted(unique, unique), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        token = extract(registered.body(), "token");

        HttpResponse<String> profile = send("POST", "/api/profiles",
                "{\"name\":\"Tester\",\"ageMode\":\"OLDER\"}", token, null);
        assertEquals(201, profile.statusCode(), profile.body());
        profileId = extract(profile.body(), "id");

        MediaItem film = items.save(MediaItem.builder()
                .type(MediaType.FILM)
                .filePath("D:/Media/Shorts.Source.mkv")
                .fileName("Shorts.Source.mkv")
                .title("Shorts Source")
                .sortTitle("shorts source")
                .build());
        itemId = film.getId();

        for (int i = 0; i < CLIP_COUNT; i++) {
            clip("clip" + i, 0);
        }
    }

    // --- the shuffle ---

    /**
     * The whole point: two viewers opening the feed do not get the same reel.
     *
     * <p>Compares whole orders rather than first clips on purpose. Two shuffles of twelve
     * agreeing outright is 1 in 12!, which is never; two agreeing on their first clip
     * alone is 1 in 12, which is a flaky test roughly once a fortnight.
     */
    @Test
    void two_viewers_do_not_get_the_same_reel() throws Exception {
        Set<List<String>> reels = new HashSet<>();
        Set<Integer> seeds = new HashSet<>();
        for (int attempt = 0; attempt < 5; attempt++) {
            HttpResponse<String> response = get("/api/media/teasers?size=" + CLIP_COUNT);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(CLIP_COUNT, labels(response.body()).size(), response.body());
            reels.add(labels(response.body()));
            seeds.add(number(response.body(), "seed"));
        }

        assertTrue(seeds.size() > 1, "a feed asked for without a seed deals a new one");
        assertTrue(reels.size() > 1,
                "the feed is still in a fixed order: every viewer got " + reels);
    }

    /** A seed is the whole contract: hand it back, get the same reel, however long after. */
    @Test
    void the_same_seed_replays_the_same_reel() throws Exception {
        HttpResponse<String> first = get("/api/media/teasers?size=" + CLIP_COUNT);
        assertEquals(200, first.statusCode(), first.body());
        int seed = number(first.body(), "seed");

        HttpResponse<String> again = get("/api/media/teasers?size=" + CLIP_COUNT + "&seed=" + seed);
        assertEquals(200, again.statusCode(), again.body());

        assertEquals(seed, number(again.body(), "seed"), "the seed comes back as it was sent");
        assertEquals(labels(first.body()), labels(again.body()));
    }

    /**
     * Paging a shuffled feed neither repeats nor loses a clip.
     *
     * <p>This is the half that random order is easy to break. Each page is its own
     * request, so an order re-rolled per request would deal page 2 from a freshly shuffled
     * deck: clips already watched come round again, others are never reached at all. The
     * seed carried from page 0 is what makes the later pages a continuation rather than a
     * new deal.
     */
    @Test
    void paging_the_shuffle_neither_repeats_nor_loses_a_clip() throws Exception {
        HttpResponse<String> firstPage = get("/api/media/teasers?page=0&size=5");
        assertEquals(200, firstPage.statusCode(), firstPage.body());
        int seed = number(firstPage.body(), "seed");
        assertEquals(CLIP_COUNT, number(firstPage.body(), "totalItems"));
        assertEquals(3, number(firstPage.body(), "totalPages"));

        List<String> seen = new ArrayList<>(labels(firstPage.body()));
        for (int page = 1; page < 3; page++) {
            HttpResponse<String> response =
                    get("/api/media/teasers?page=" + page + "&size=5&seed=" + seed);
            assertEquals(200, response.statusCode(), response.body());
            seen.addAll(labels(response.body()));
        }

        assertEquals(CLIP_COUNT, seen.size(), "every clip appears exactly once: " + seen);
        assertEquals(CLIP_COUNT, new HashSet<>(seen).size(),
                "no clip is repeated or lost: " + seen);
    }

    /**
     * Ranking a clip still puts it first, shuffle or no shuffle.
     *
     * <p>{@code sortOrder} is the one hand-curated thing about this feed. A random order
     * that ignored it would not fail anywhere — it would just quietly stop honouring
     * every pin an admin had already set.
     */
    @Test
    void a_pinned_clip_leads_every_shuffle() throws Exception {
        clip("pinned", 1);

        for (int attempt = 0; attempt < 5; attempt++) {
            HttpResponse<String> response = get("/api/media/teasers?size=" + (CLIP_COUNT + 1));
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("pinned", labels(response.body()).get(0),
                    "the pinned clip was shuffled into the pack");
        }
    }

    /** Unpublished and still-encoding clips stay out of the feed the shuffle draws from. */
    @Test
    void only_published_ready_clips_are_dealt() throws Exception {
        clips.save(baseClip("unpublished", 0).published(false).build());
        clips.save(baseClip("encoding", 0).state(TeaserClip.State.GENERATING).build());

        HttpResponse<String> response = get("/api/media/teasers?size=50");
        assertEquals(200, response.statusCode(), response.body());

        assertEquals(CLIP_COUNT, number(response.body(), "totalItems"));
        assertTrue(new HashSet<>(labels(response.body())).stream().noneMatch(
                label -> label.equals("unpublished") || label.equals("encoding")),
                "a clip nobody has published is not in the reel: " + labels(response.body()));
    }

    // --- helpers ---

    private TeaserClip clip(String label, int sortOrder) {
        return clips.save(baseClip(label, sortOrder).build());
    }

    private TeaserClip.TeaserClipBuilder baseClip(String label, int sortOrder) {
        return TeaserClip.builder()
                .mediaItemId(itemId)
                .state(TeaserClip.State.READY)
                .startSeconds(60)
                .endSeconds(75)
                .label(label)
                .sourcePath("D:/Media/Shorts.Source.mkv")
                .outputPath("teasers/" + label + ".mp4")
                .published(true)
                .sortOrder(sortOrder);
    }

    /** The clip labels in the order the feed dealt them — the order is the whole subject. */
    private static List<String> labels(String json) {
        List<String> found = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"label\":\"([^\"]+)\"").matcher(json);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, token, profileId);
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

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int number(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        assertTrue(matcher.find(), "no " + field + " in " + json);
        return Integer.parseInt(matcher.group(1));
    }
}
