package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.kido.media.MediaPaths;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.catalog.MetadataSource;
import com.example.kido.media.library.LibraryIngestService;

/**
 * Covers the fix-wrong-metadata workflow against a real folder on disk.
 *
 * <p>A real library root is needed because the candidate list is built by re-reading
 * the folder — sidecars the scanner skipped, the folder name — and mocking that away
 * would test nothing worth testing.
 *
 * <p>The load-bearing test here is {@link #correctionSurvivesARescan()}. Everything
 * else in this feature is pointless if a fix is silently undone the next time the
 * scanner touches the file, and that failure would look like the fix never saving at
 * all rather than like a scanner problem.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaMatchFixIntegrationTest {

    private static final String ADMIN_KEY = "test-admin-key";

    private static Path mediaRoot;
    private static Path filmFolder;
    private static Path filmFile;

    /**
     * A folder shaped like the case this screen exists for: the filename is a mangled
     * release name, the folder name is correct, and there is a sidecar belonging to a
     * different film entirely.
     */
    @DynamicPropertySource
    static void configureLibrary(DynamicPropertyRegistry registry) {
        try {
            mediaRoot = Files.createTempDirectory("kido-matchfix-root");
            filmFolder = Files.createDirectories(mediaRoot.resolve("Arrival (2016)"));

            filmFile = filmFolder.resolve("arrvl.2016.1080p.WEB-DL.x264-GRP.mkv");
            Files.write(filmFile, new byte[64 * 1024 * 1024]);

            // A sidecar for the wrong film, which is how these mismatches happen.
            Files.writeString(filmFolder.resolve("Interstellar.nfo"), """
                    <movie>
                      <title>Interstellar</title>
                      <year>2014</year>
                      <plot>A team travels through a wormhole.</plot>
                      <runtime>169</runtime>
                      <genre>Science Fiction</genre>
                      <uniqueid type="tmdb">157336</uniqueid>
                    </movie>
                    """, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not build matchfix fixture", ex);
        }
        registry.add("app.media.roots", () -> mediaRoot.toString());
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (mediaRoot == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(mediaRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort on a temp directory.
                }
            });
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    MediaItemRepository items;

    @Autowired
    LibraryIngestService ingest;

    @Autowired
    MediaPaths paths;

    private final HttpClient http = HttpClient.newHttpClient();
    private String parentToken;
    private String childToken;
    private String itemId;

    private HttpResponse<String> send(String method, String path, String json,
                                      String bearer, String adminKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (adminKey != null) {
            builder.header("X-Admin-Key", adminKey);
        }
        builder.method(method, json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String register(String prefix, String role) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = send("POST", "/api/auth/register", """
                {"username":"%s_%s","email":"%s_%s@example.com","password":"pw123456",
                 "displayName":"Fix Tester","role":"%s"}
                """.formatted(prefix, unique, prefix, unique, role), null, null);
        assertEquals(201, registered.statusCode(), registered.body());
        return extract(registered.body(), "token");
    }

    @BeforeEach
    void setUp() throws Exception {
        parentToken = register("fixowner", "PARENT");
        childToken = register("fixkid", "CHILD");

        // Index the fixture through the real ingest path, so the row under test is the
        // one the scanner would actually produce.
        items.deleteAll();
        ingest.ingest(filmFile, paths.libraryRoots().get(0));
        MediaItem indexed = items.findByFilePath(
                filmFile.toAbsolutePath().normalize().toString()).orElseThrow();
        itemId = indexed.getId();
    }

    // --- gating ---

    @Test
    void queueIsOwnerOnly() throws Exception {
        assertEquals(403, send("GET", "/api/media/admin/matches", null, parentToken, null)
                .statusCode());
        assertEquals(403, send("GET", "/api/media/admin/matches", null, childToken, ADMIN_KEY)
                .statusCode());
        assertEquals(403, send("GET", "/api/media/admin/matches", null, null, ADMIN_KEY)
                .statusCode());
        assertEquals(200, send("GET", "/api/media/admin/matches", null, parentToken, ADMIN_KEY)
                .statusCode());
    }

    @Test
    void applyingACorrectionIsOwnerOnly() throws Exception {
        assertEquals(403, send("PUT", "/api/media/admin/matches/" + itemId,
                "{\"title\":\"Hijacked\"}", childToken, ADMIN_KEY).statusCode());
    }

    // --- the queue ---

    /**
     * The scanner had only a mangled filename to work from, so the item lands in the
     * queue. Note it picked up the wrong sidecar's title is not asserted here — what
     * matters is that a guessed item is queued.
     */
    @Test
    void filenameGuessedItemsAppearInTheQueue() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/admin/matches", null, parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains(itemId), response.body());
        assertTrue(response.body().contains("arrvl.2016.1080p.WEB-DL.x264-GRP.mkv"),
                response.body());
    }

    /** The screen shows the real path — the one place the API exposes it. */
    @Test
    void fixScreenExposesTheRealPathAndAPromise() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/admin/matches/" + itemId, null, parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());

        assertTrue(response.body().contains("Arrival (2016)"), response.body());
        assertTrue(response.body().contains("folderPath"), response.body());
        // The promise is served from the backend so client copy cannot drift from it.
        assertTrue(response.body().contains("nothing on the disk is renamed"),
                response.body());
    }

    // --- candidates ---

    @Test
    void candidatesIncludeTheFolderNameAndTheStraySidecar() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/media/admin/matches/" + itemId, null, parentToken, ADMIN_KEY);

        // The folder name is the correct answer here, and is offered as a candidate.
        // Checked as a candidate title, not just anywhere in the body — the folder path
        // itself contains "Arrival", so a bare contains() would pass without a candidate.
        assertTrue(response.body().contains("\"title\":\"Arrival\""), response.body());
        assertTrue(response.body().contains("\"origin\":\"folder\""), response.body());
        // The sidecar for the wrong film is offered too, labelled as a sidecar so the
        // owner can judge it rather than being told it is authoritative.
        assertTrue(response.body().contains("Interstellar"), response.body());
        assertTrue(response.body().contains("\"origin\":\"sidecar\""), response.body());
        assertTrue(response.body().contains("matchPercent"), response.body());
    }

    /** Searching must re-rank, or the wrong existing title stays pinned at the top. */
    @Test
    void searchRanksAgainstTheQueryNotTheCurrentTitle() throws Exception {
        HttpResponse<String> response = send("GET",
                "/api/media/admin/matches/" + itemId + "?q=Interstellar",
                null, parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());

        // With that query, the Interstellar sidecar must outrank the folder name.
        int interstellar = response.body().indexOf("Interstellar");
        int arrival = response.body().indexOf("\"title\":\"Arrival\"");
        assertTrue(interstellar >= 0, response.body());
        assertTrue(arrival < 0 || interstellar < arrival,
                "Interstellar should rank first for that query: " + response.body());
    }

    /** Whatever the owner types is always offered, however badly it scores. */
    @Test
    void typedTitleIsAlwaysOffered() throws Exception {
        HttpResponse<String> response = send("GET",
                "/api/media/admin/matches/" + itemId + "?q="
                        + URLEncoder.encode("Something Completely Different", StandardCharsets.UTF_8),
                null, parentToken, ADMIN_KEY);
        assertTrue(response.body().contains("\"origin\":\"typed\""), response.body());
        assertTrue(response.body().contains("Something Completely Different"), response.body());
    }

    // --- applying ---

    @Test
    void applyingATypedTitleSetsItAndLocksIt() throws Exception {
        HttpResponse<String> response = send("PUT", "/api/media/admin/matches/" + itemId,
                "{\"title\":\"Arrival\",\"year\":2016}", parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"metadataSource\":\"MANUAL\""), response.body());
        assertTrue(response.body().contains("Nothing on disk was changed"), response.body());

        MediaItem reloaded = items.findById(itemId).orElseThrow();
        assertEquals("Arrival", reloaded.getTitle());
        assertEquals(2016, reloaded.getYear());
        assertEquals("arrival", reloaded.getSortTitle());
        assertEquals(MetadataSource.MANUAL, reloaded.getMetadataSource());
    }

    /** Choosing a rich candidate brings its metadata across, not just the title. */
    @Test
    void applyingASidecarCandidateCopiesItsMetadata() throws Exception {
        HttpResponse<String> screen = send("GET",
                "/api/media/admin/matches/" + itemId + "?q=Interstellar",
                null, parentToken, ADMIN_KEY);
        String candidateId = extractCandidateIdFor(screen.body(), "Interstellar");
        assertNotNull(candidateId, screen.body());

        HttpResponse<String> applied = send("PUT", "/api/media/admin/matches/" + itemId,
                "{\"candidateId\":\"%s\",\"title\":\"Interstellar\"}".formatted(candidateId),
                parentToken, ADMIN_KEY);
        assertEquals(200, applied.statusCode(), applied.body());

        MediaItem reloaded = items.findById(itemId).orElseThrow();
        assertEquals("Interstellar", reloaded.getTitle());
        assertEquals(2014, reloaded.getYear());
        assertEquals(169, reloaded.getRuntimeMinutes());
        assertEquals("157336", reloaded.getTmdbId());
        assertTrue(reloaded.getGenres().contains("Science Fiction"), "" + reloaded.getGenres());
    }

    @Test
    void applyingWithNeitherCandidateNorTitleIsRejected() throws Exception {
        assertEquals(400, send("PUT", "/api/media/admin/matches/" + itemId,
                "{}", parentToken, ADMIN_KEY).statusCode());
    }

    @Test
    void applyingAStaleCandidateIdConflicts() throws Exception {
        assertEquals(409, send("PUT", "/api/media/admin/matches/" + itemId,
                "{\"candidateId\":\"c999\"}", parentToken, ADMIN_KEY).statusCode());
    }

    @Test
    void correctedItemLeavesTheQueue() throws Exception {
        send("PUT", "/api/media/admin/matches/" + itemId,
                "{\"title\":\"Arrival\",\"year\":2016}", parentToken, ADMIN_KEY);

        HttpResponse<String> queue =
                send("GET", "/api/media/admin/matches", null, parentToken, ADMIN_KEY);
        assertTrue(!queue.body().contains(itemId), queue.body());
        assertTrue(queue.body().contains("\"totalRemaining\":0"), queue.body());
    }

    /**
     * The whole feature rests on this. A rescan re-reads the file, and without the
     * MANUAL lock it would overwrite the correction with a fresh filename guess.
     */
    @Test
    void correctionSurvivesARescan() throws Exception {
        send("PUT", "/api/media/admin/matches/" + itemId,
                "{\"title\":\"Arrival\",\"year\":2016}", parentToken, ADMIN_KEY);

        // Force the scanner down the "content changed" path, which is what rewrites
        // metadata; touching the file is exactly what would happen on a real rescan.
        MediaItem before = items.findById(itemId).orElseThrow();
        before.setFileSize(before.getFileSize() - 1);
        items.save(before);

        ingest.ingest(filmFile, paths.libraryRoots().get(0));

        MediaItem after = items.findById(itemId).orElseThrow();
        assertEquals("Arrival", after.getTitle(), "the rescan overwrote a manual fix");
        assertEquals(2016, after.getYear());
        assertEquals(MetadataSource.MANUAL, after.getMetadataSource());
    }

    // --- escape hatches ---

    @Test
    void reclassifyingMovesTheCategoryAndLocksIt() throws Exception {
        HttpResponse<String> response = send("POST",
                "/api/media/admin/matches/" + itemId + "/reclassify",
                "{\"type\":\"ours\"}", parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"type\":\"HOME_VIDEO\""), response.body());
        assertTrue(response.body().contains("has not moved on disk"), response.body());

        MediaItem reloaded = items.findById(itemId).orElseThrow();
        assertEquals(MediaType.HOME_VIDEO, reloaded.getType());
        assertTrue(reloaded.isTypeLocked());
    }

    /** A locked type must also survive the scanner, which derives type from the root. */
    @Test
    void reclassificationSurvivesARescan() throws Exception {
        send("POST", "/api/media/admin/matches/" + itemId + "/reclassify",
                "{\"type\":\"HOME_VIDEO\"}", parentToken, ADMIN_KEY);

        MediaItem before = items.findById(itemId).orElseThrow();
        before.setFileSize(before.getFileSize() - 1);
        items.save(before);
        ingest.ingest(filmFile, paths.libraryRoots().get(0));

        MediaItem after = items.findById(itemId).orElseThrow();
        assertEquals(MediaType.HOME_VIDEO, after.getType(),
                "the rescan reverted a manual reclassify");
    }

    @Test
    void rejectsUnknownCategory() throws Exception {
        assertEquals(400, send("POST", "/api/media/admin/matches/" + itemId + "/reclassify",
                "{\"type\":\"nonsense\"}", parentToken, ADMIN_KEY).statusCode());
    }

    @Test
    void unmatchingHidesItWithoutDeletingAnything() throws Exception {
        HttpResponse<String> response = send("POST",
                "/api/media/admin/matches/" + itemId + "/unmatch", null, parentToken, ADMIN_KEY);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("untouched on disk"), response.body());

        MediaItem reloaded = items.findById(itemId).orElseThrow();
        assertTrue(reloaded.isHidden());
        // The row and the file both survive.
        assertTrue(Files.exists(filmFile), "the file must not be deleted");

        HttpResponse<String> queue =
                send("GET", "/api/media/admin/matches", null, parentToken, ADMIN_KEY);
        assertTrue(!queue.body().contains(itemId), queue.body());
    }

    @Test
    void resetPutsItBackInTheQueue() throws Exception {
        send("PUT", "/api/media/admin/matches/" + itemId,
                "{\"title\":\"Arrival\"}", parentToken, ADMIN_KEY);
        assertEquals(200, send("POST", "/api/media/admin/matches/" + itemId + "/reset",
                null, parentToken, ADMIN_KEY).statusCode());

        HttpResponse<String> queue =
                send("GET", "/api/media/admin/matches", null, parentToken, ADMIN_KEY);
        assertTrue(queue.body().contains(itemId), queue.body());
    }

    @Test
    void unknownItemIsNotFound() throws Exception {
        assertEquals(404, send("GET", "/api/media/admin/matches/nope",
                null, parentToken, ADMIN_KEY).statusCode());
    }

    /** Extracts the candidate id whose object contains the given title. */
    private static String extractCandidateIdFor(String json, String title) {
        Matcher matcher = Pattern.compile(
                "\\{\"id\":\"(c\\d+)\",\"title\":\"" + Pattern.quote(title) + "\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
