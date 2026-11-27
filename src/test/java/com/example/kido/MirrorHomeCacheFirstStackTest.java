package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.example.kido.media.dto.HomeDtos.MirrorRailDto;
import com.example.kido.media.dto.HomeDtos.MirrorTileDto;
import com.example.kido.mymirror.MirrorSessionProvider;
import com.example.kido.mymirror.MirrorStreamSigner;
import com.example.kido.mymirror.catalogue.MirrorCatalogueItemRepository;
import com.example.kido.mymirror.catalogue.MirrorCatalogueProperties;
import com.example.kido.mymirror.catalogue.MirrorCatalogueRelationRepository;
import com.example.kido.mymirror.catalogue.MirrorCatalogueSnapshotRepository;
import com.example.kido.mymirror.catalogue.MirrorCatalogueStore;

/**
 * Full-stack proof of the #1 cache-first guarantee through the <b>real</b> wiring: the whole request
 * path, the real {@link com.example.kido.mymirror.service.MirrorHomeService}, the real
 * {@link MirrorCatalogueStore} over H2 (PostgreSQL mode), persistence <b>and</b> cache-first both ON.
 *
 * <p>Where {@code MirrorHomeEndpointIntegrationTest} mocks the service to assert the controller
 * contract, this test uses no mock for the service: a snapshot published into L2 must be served by
 * {@code /api/media/home} as {@code X-Mirror-Catalogue-State: cached} while the upstream HTTP client
 * is bound to a {@link MockRestServiceServer} that <b>expects nothing</b> — so a single provider call
 * would fail the test. That closes the loop the two half-tests leave open: a usable fresh catalogue
 * causes <b>zero</b> upstream catalogue requests, end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.mymirror.catalogue.persistence.enabled=true",
        "app.mymirror.catalogue.cache-first.enabled=true",
        "app.mymirror.catalogue.scope=stacktest",
        "app.mymirror.catalogue.freshness.soft-ttl=6h",
        "app.mymirror.catalogue.freshness.hard-expiry=168h",
})
class MirrorHomeCacheFirstStackTest {

    private static final String OPERATOR = "t_hash_t=local-stack-fixture";

    @DynamicPropertySource
    static void operatorFile(DynamicPropertyRegistry registry) throws IOException {
        Path file = Files.createTempFile("kidoo-catalogue-stack-", ".session");
        file.toFile().deleteOnExit();
        Files.writeString(file, OPERATOR);
        registry.add("app.mymirror.session.session-file", file::toString);
    }

    @Autowired MirrorSessionProvider sessions;
    @Autowired MirrorStreamSigner signer;

    private void seedOperatorSnapshot() {
        String epoch = sessions.catalogueEpoch(OPERATOR, sessions.generation(OPERATOR));
        assertTrue(store.publishInNamespace(store.namespace(epoch, signer), "nf", sampleHome(), true, 1));
    }

    @LocalServerPort
    int port;

    @Autowired MirrorCatalogueStore store;
    @Autowired MirrorCatalogueItemRepository items;
    @Autowired MirrorCatalogueRelationRepository relations;
    @Autowired MirrorCatalogueSnapshotRepository snapshots;

    /** The @Primary upstream client that BrowseRepository uses for every provider call. */
    @Autowired @Qualifier("restTemplate") RestTemplate upstream;

    private final HttpClient http = HttpClient.newHttpClient();
    private MockRestServiceServer upstreamGuard;

    @BeforeEach
    void clean() {
        relations.deleteAll();
        items.deleteAll();
        snapshots.deleteAll();
        // Bind the upstream HTTP client to a server that expects NO requests: any provider call made
        // while a fresh catalogue is on hand throws "No further requests expected" and fails the test.
        upstreamGuard = MockRestServiceServer.createServer(upstream);
    }

    @AfterEach
    void resetUpstream() {
        // Restore the client's real request factory so a later test in the suite is unaffected.
        upstreamGuard.reset();
    }

    // ---- helpers ----

    private static MirrorTileDto tile(String id, String title) {
        return new MirrorTileDto(id, title, "https://imgcdn.kim/nf/v/" + id + ".jpg", null);
    }

    private static MirrorRailDto rail(String key, String title, MirrorTileDto... tiles) {
        return new MirrorRailDto(key, title, Arrays.asList(tiles));
    }

    /** A fresh Netflix home snapshot: three categories, five titles. */
    private static List<MirrorRailDto> sampleHome() {
        return List.of(
                rail("top10", "Top 10", tile("101", "Alpha"), tile("102", "Beta"), tile("103", "Gamma")),
                rail("trending", "Trending", tile("201", "Delta")),
                rail("because", "Because you watched", tile("202", "Epsilon")));
    }

    @Test
    void aFreshL2SnapshotIsServedAsCachedWithZeroUpstreamHttp() throws Exception {
        seedOperatorSnapshot();

        // The operator namespace has no in-memory entry. The request must hit the persisted snapshot.
        HttpResponse<String> home = home(account("PARENT"), null, "nf");

        assertEquals(200, home.statusCode(), home.body());
        assertEquals("cached", home.headers().firstValue("X-Mirror-Catalogue-State").orElse(null), home.body());
        // Content from the persisted snapshot survived to the response.
        assertTrue(home.body().contains("\"id\":\"101\""), home.body());
        assertTrue(home.body().contains("\"id\":\"202\""), home.body());
        // The decisive assertion: not one provider HTTP call was made while serving the fresh catalogue.
        upstreamGuard.verify();
    }

    @Test
    void concurrentEndpointRequestsAllServeTheFreshL2SnapshotWithZeroUpstream() throws Exception {
        seedOperatorSnapshot();
        String[] account = account("PARENT");

        int clients = 16;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try { return home(account, null, "nf"); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }, pool));
            }
            for (CompletableFuture<HttpResponse<String>> call : calls) {
                HttpResponse<String> home = call.get(30, TimeUnit.SECONDS);
                assertEquals(200, home.statusCode(), home.body());
                assertEquals("cached", home.headers().firstValue("X-Mirror-Catalogue-State").orElse(null), home.body());
            }
        } finally {
            pool.shutdownNow();
        }
        // Every one of the concurrent fresh hits bypassed upstream entirely.
        upstreamGuard.verify();
    }

    // ---- request helpers (same shape as MirrorHomeEndpointIntegrationTest) ----

    private HttpResponse<String> home(String[] account, String mirrorCookie, String ott) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/media/home"))
                .header("Authorization", "Bearer " + account[0]);
        if (account[1] != null) req.header("X-Profile-Id", account[1]);
        if (mirrorCookie != null) req.header("X-Mirror-Cookie", mirrorCookie);
        if (ott != null) req.header("X-Mirror-Ott", ott);
        return http.send(req.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String[] account(String role) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = post("/api/auth/register", """
                {"username":"st_%s","email":"st_%s@example.com","password":"pw123456",
                 "displayName":"Stack Test","role":"%s"}
                """.formatted(unique, unique, role), null);
        assertEquals(201, registered.statusCode(), registered.body());
        String bearer = extract(registered.body(), "token");
        HttpResponse<String> profile = post("/api/profiles", "{\"name\":\"Tester\",\"ageMode\":\"OLDER\"}", bearer);
        return new String[] {bearer, profile.statusCode() == 201 ? extract(profile.body(), "id") : null};
    }

    private HttpResponse<String> post(String path, String json, String bearer) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (bearer != null) req.header("Authorization", "Bearer " + bearer);
        return http.send(req.POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String extract(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
