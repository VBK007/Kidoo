package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
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
import com.example.kido.media.engagement.MediaItemCommentRepository;
import com.example.kido.profile.Profile;
import com.example.kido.profile.ProfileRepository;

/**
 * Drives the comment thread over real HTTP against in-memory H2.
 *
 * <p>What is worth proving here is not that a row round-trips but who may touch it: a
 * comment is written by a profile and moderated by an account, and those two are
 * deliberately different answers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaCommentIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    @Autowired
    ProfileRepository profiles;

    @Autowired
    MediaItemCommentRepository comments;

    private final HttpClient http = HttpClient.newHttpClient();

    /** The household: one PARENT account with two profiles on it. */
    private String token;
    private String parentProfileId;
    private String childProfileId;
    private String itemId;

    // --- helpers ----------------------------------------------------------

    private String base() {
        return "http://localhost:" + port;
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

    private String str(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private long num(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(body);
        return m.find() ? Long.parseLong(m.group(1)) : Long.MIN_VALUE;
    }

    private boolean flag(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)").matcher(body);
        return m.find() && Boolean.parseBoolean(m.group(1));
    }

    /** Registers an account of the given role and returns its bearer token. */
    private String register(String prefix, String role) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"%s_%s","email":"%s_%s@example.com",
                 "password":"pw123456","displayName":"Comment Tester","role":"%s"}
                """.formatted(prefix, unique, prefix, unique, role), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        return str(registered.body(), "token");
    }

    private String addProfile(String bearer, String name) throws Exception {
        HttpResponse<String> created = send("POST", "/api/profiles",
                "{\"name\":\"" + name + "\",\"ageMode\":\"OLDER\"}", bearer, null);
        assertEquals(201, created.statusCode(), created.body());
        return str(created.body(), "id");
    }

    /** Unique file path per row: the catalog keys on it, and rows outlive one test. */
    private String insertFilm(String title) {
        String slug = title.replace(' ', '.') + "." + UUID.randomUUID();
        return items.save(MediaItem.builder()
                .type(MediaType.FILM)
                .libraryName(MediaType.FILM.label())
                .filePath("D:/Media/" + slug + ".mkv")
                .fileName(slug + ".mkv")
                .folderPath("D:/Media")
                .fileSize(1_400_000_000L)
                .title(title)
                .sortTitle(title.toLowerCase())
                .year(2019)
                .runtimeMinutes(148)
                .rating(8.4)
                .plot("A film with something to say about it.")
                .castMembers("Toshiro Mifune, Takashi Shimura")
                .mediaInfo(MediaInfo.builder()
                        .container("mkv")
                        .videoCodec("h264")
                        .audioCodecs("aac")
                        .width(1920)
                        .height(1080)
                        .bitrate(8_000_000L)
                        .durationSeconds(8880.0)
                        .probedAt(Instant.now())
                        .build())
                .build()).getId();
    }

    private String postComment(String bearer, String profileId, String body) throws Exception {
        HttpResponse<String> res = send("POST", "/api/media/items/" + itemId + "/comments",
                "{\"body\":\"" + body + "\"}", bearer, profileId);
        assertEquals(201, res.statusCode(), res.body());
        return str(res.body(), "id");
    }

    @BeforeEach
    void setUp() throws Exception {
        comments.deleteAll();
        token = register("household", "PARENT");
        parentProfileId = addProfile(token, "Dad");
        childProfileId = addProfile(token, "Maya");
        itemId = insertFilm("Seven Samurai");
    }

    // --- posting and reading ---------------------------------------------

    @Test
    void a_comment_is_posted_and_comes_back_attributed_to_its_profile() throws Exception {
        HttpResponse<String> posted = send("POST", "/api/media/items/" + itemId + "/comments",
                "{\"body\":\"  The rain fight is the best scene ever shot.  \"}",
                token, childProfileId);
        assertEquals(201, posted.statusCode(), posted.body());

        // Trimmed on the way in, and attributed to the profile rather than the account.
        assertEquals("The rain fight is the best scene ever shot.", str(posted.body(), "body"));
        assertEquals("Maya", str(posted.body(), "authorName"));
        assertEquals(childProfileId, str(posted.body(), "profileId"));
        assertTrue(flag(posted.body(), "mine"));
        assertNotNull(str(posted.body(), "createdAt"));

        HttpResponse<String> listed = send("GET", "/api/media/items/" + itemId + "/comments",
                null, token, childProfileId);
        assertEquals(200, listed.statusCode(), listed.body());
        assertEquals(1, num(listed.body(), "totalItems"));
        assertTrue(listed.body().contains("The rain fight"));
    }

    @Test
    void a_thread_reads_newest_first_and_pages() throws Exception {
        postComment(token, childProfileId, "first");
        postComment(token, childProfileId, "second");
        postComment(token, childProfileId, "third");

        HttpResponse<String> firstPage = send("GET",
                "/api/media/items/" + itemId + "/comments?page=0&size=2", null,
                token, childProfileId);
        assertEquals(200, firstPage.statusCode(), firstPage.body());
        assertEquals(3, num(firstPage.body(), "totalItems"));
        assertEquals(2, num(firstPage.body(), "totalPages"));

        // Newest first: the last thing said leads the thread.
        assertTrue(firstPage.body().indexOf("third") < firstPage.body().indexOf("second"));
        assertTrue(!firstPage.body().contains("\"body\":\"first\""));
    }

    @Test
    void a_comment_written_by_someone_else_is_not_mine() throws Exception {
        postComment(token, childProfileId, "Mayas take");

        HttpResponse<String> asDad = send("GET", "/api/media/items/" + itemId + "/comments",
                null, token, parentProfileId);
        assertEquals(200, asDad.statusCode(), asDad.body());
        assertTrue(!flag(asDad.body(), "mine"));
        // Dad is a PARENT on the same account, so he may still take it down.
        assertTrue(flag(asDad.body(), "canDelete"));
    }

    /** A rename should re-sign every comment the profile ever wrote, not just new ones. */
    @Test
    void listing_shows_the_profiles_current_name_after_a_rename() throws Exception {
        postComment(token, childProfileId, "Signed under the old name");

        Profile maya = profiles.findById(childProfileId).orElseThrow();
        maya.setName("Maya R");
        profiles.save(maya);

        HttpResponse<String> listed = send("GET", "/api/media/items/" + itemId + "/comments",
                null, token, childProfileId);
        assertEquals("Maya R", str(listed.body(), "authorName"));
    }

    // --- the count on the detail screen -----------------------------------

    @Test
    void movie_detail_carries_the_comment_count_beside_views_and_likes() throws Exception {
        HttpResponse<String> before = send("GET", "/api/media/items/" + itemId, null,
                token, childProfileId);
        assertEquals(200, before.statusCode(), before.body());
        assertEquals(0, num(before.body(), "commentCount"));

        postComment(token, childProfileId, "one");
        postComment(token, parentProfileId, "two");

        HttpResponse<String> after = send("GET", "/api/media/items/" + itemId, null,
                token, childProfileId);
        assertEquals(2, num(after.body(), "commentCount"));
        // The detail screen's other three signals are unchanged by commenting.
        assertEquals(0, num(after.body(), "viewCount"));
        assertEquals(0, num(after.body(), "likeCount"));
        assertTrue(after.body().contains("Toshiro Mifune"));
    }

    // --- editing -----------------------------------------------------------

    @Test
    void the_author_may_rewrite_their_own_comment_and_it_is_marked_edited() throws Exception {
        String commentId = postComment(token, childProfileId, "Too long in the middle");

        HttpResponse<String> edited = send("PUT",
                "/api/media/items/" + itemId + "/comments/" + commentId,
                "{\"body\":\"Rewatched it. The middle earns itself.\"}", token, childProfileId);
        assertEquals(200, edited.statusCode(), edited.body());
        assertEquals("Rewatched it. The middle earns itself.", str(edited.body(), "body"));
        assertNotNull(str(edited.body(), "editedAt"));
    }

    /** Moderation is removal, never rewriting: a parent must not reword a child. */
    @Test
    void a_parent_cannot_edit_another_profiles_comment() throws Exception {
        String commentId = postComment(token, childProfileId, "Mayas words");

        HttpResponse<String> edited = send("PUT",
                "/api/media/items/" + itemId + "/comments/" + commentId,
                "{\"body\":\"Words Maya never said\"}", token, parentProfileId);
        assertEquals(403, edited.statusCode(), edited.body());
        assertEquals("Mayas words", comments.findById(commentId).orElseThrow().getBody());
    }

    // --- deleting ----------------------------------------------------------

    @Test
    void the_author_may_delete_their_own() throws Exception {
        String commentId = postComment(token, childProfileId, "Never mind");

        assertEquals(204, send("DELETE",
                "/api/media/items/" + itemId + "/comments/" + commentId, null,
                token, childProfileId).statusCode());
        assertTrue(comments.findById(commentId).isEmpty());
    }

    @Test
    void a_parent_may_delete_anything_on_their_own_account() throws Exception {
        String commentId = postComment(token, childProfileId, "Something to take down");

        assertEquals(204, send("DELETE",
                "/api/media/items/" + itemId + "/comments/" + commentId, null,
                token, parentProfileId).statusCode());
        assertTrue(comments.findById(commentId).isEmpty());
    }

    /** A parent moderates their own household and nobody else's. */
    @Test
    void a_parent_on_another_account_cannot_delete_it() throws Exception {
        String commentId = postComment(token, childProfileId, "Not yours to remove");

        String otherToken = register("neighbour", "PARENT");
        String otherProfile = addProfile(otherToken, "Neighbour");

        HttpResponse<String> refused = send("DELETE",
                "/api/media/items/" + itemId + "/comments/" + commentId, null,
                otherToken, otherProfile);
        assertEquals(403, refused.statusCode(), refused.body());
        assertTrue(comments.findById(commentId).isPresent());
    }

    // --- validation and wrong ids ------------------------------------------

    @Test
    void an_empty_or_oversized_comment_is_refused() throws Exception {
        assertEquals(400, send("POST", "/api/media/items/" + itemId + "/comments",
                "{\"body\":\"\"}", token, childProfileId).statusCode());
        assertEquals(400, send("POST", "/api/media/items/" + itemId + "/comments",
                "{\"body\":\"   \"}", token, childProfileId).statusCode());
        assertEquals(400, send("POST", "/api/media/items/" + itemId + "/comments",
                "{\"body\":\"" + "x".repeat(1001) + "\"}", token, childProfileId).statusCode());
    }

    @Test
    void commenting_on_an_unknown_item_is_not_found() throws Exception {
        assertEquals(404, send("POST", "/api/media/items/no-such-item/comments",
                "{\"body\":\"hello\"}", token, childProfileId).statusCode());
    }

    /** A real comment id under the wrong item must not resolve. */
    @Test
    void a_comment_id_under_another_item_is_not_found() throws Exception {
        String commentId = postComment(token, childProfileId, "On Seven Samurai");
        String otherItem = insertFilm("Yojimbo");

        assertEquals(404, send("DELETE",
                "/api/media/items/" + otherItem + "/comments/" + commentId, null,
                token, childProfileId).statusCode());
    }

    @Test
    void comments_require_authentication() throws Exception {
        assertEquals(403, send("GET", "/api/media/items/" + itemId + "/comments",
                null, null, null).statusCode());
        assertEquals(403, send("POST", "/api/media/items/" + itemId + "/comments",
                "{\"body\":\"anonymous\"}", null, null).statusCode());
    }
}
