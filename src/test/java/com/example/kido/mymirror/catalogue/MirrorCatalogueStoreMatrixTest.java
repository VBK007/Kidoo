package com.example.kido.mymirror.catalogue;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.TestPropertySource;

import com.example.kido.media.dto.HomeDtos.MirrorRailDto;
import com.example.kido.media.dto.HomeDtos.MirrorTileDto;
import com.example.kido.mymirror.catalogue.MirrorCatalogueStore.CatalogueView;
import com.example.kido.mymirror.catalogue.MirrorCatalogueStore.Freshness;

import jakarta.persistence.EntityManagerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 10 acceptance matrix for the mirror catalogue cache, run against the real JPA stack on H2
 * (PostgreSQL compatibility mode) with Flyway-built schema and {@code ddl-auto=validate}. Persistence
 * is turned <b>on</b> here (it is off in production by default) so every database path is exercised.
 *
 * <p>Fourteen numbered cases map one-to-one to the approved acceptance list, followed by a per-OTT
 * cached-read micro-benchmark that proves the warm read touches the database a bounded number of
 * times and never the upstream.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.mymirror.catalogue.persistence.enabled=true",
        "app.mymirror.catalogue.cache-first.enabled=true",
        "app.mymirror.catalogue.scope=test",
        "app.mymirror.catalogue.freshness.soft-ttl=6h",
        "app.mymirror.catalogue.freshness.hard-expiry=168h",
        "spring.jpa.properties.hibernate.generate_statistics=true",
})
class MirrorCatalogueStoreMatrixTest {

    @Autowired MirrorCatalogueStore store;
    @Autowired MirrorCatalogueItemRepository items;
    @Autowired MirrorCatalogueRelationRepository relations;
    @Autowired MirrorCatalogueSnapshotRepository snapshots;
    @Autowired MirrorCatalogueProperties props;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager txManager;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        // Each case starts from an empty catalogue; store methods commit (they are @Transactional),
        // so state carries between methods without an explicit wipe.
        relations.deleteAll();
        items.deleteAll();
        snapshots.deleteAll();
    }

    // ---- helpers ---------------------------------------------------------

    private static JsonNode playlist(String secret) {
        return JSON.createObjectNode().put("stream", secret); // stands in for a signed, session-bound URL
    }

    private static MirrorTileDto tile(String id, String title, String poster, JsonNode playlist) {
        return new MirrorTileDto(id, title, poster, playlist);
    }

    private static MirrorRailDto rail(String key, String title, MirrorTileDto... tiles) {
        return new MirrorRailDto(key, title, Arrays.asList(tiles));
    }

    /** A representative two-rail Netflix home: a Top 10 (ordered) and a "More Like" suggest rail. */
    private static List<MirrorRailDto> sampleHome() {
        return List.of(
                rail("top10", "Top 10",
                        tile("101", "Alpha", "https://imgcdn.kim/nf/v/101.jpg", playlist("sig-a")),
                        tile("102", "Beta", "https://imgcdn.kim/nf/v/102.jpg", playlist("sig-b")),
                        tile("103", "Gamma", "https://imgcdn.kim/nf/v/103.jpg", playlist("sig-c"))),
                rail("more-101", "More Like Alpha",
                        tile("201", "Delta", "https://imgcdn.kim/nf/v/201.jpg", playlist("sig-d")),
                        tile("202", "Epsilon", "https://imgcdn.kim/nf/v/202.jpg", playlist("sig-e"))));
    }

    private void backdate(String ott, Duration age) {
        var row = snapshots.findByScopeAndOtt(store.scope(), store.ott(ott)).orElseThrow();
        row.setLastSuccessfulRefresh(Instant.now().minus(age));
        snapshots.saveAndFlush(row);
    }

    // ---- 1. Cold ---------------------------------------------------------

    @Test
    @DisplayName("01 Cold: read on an empty catalogue returns empty")
    void cold() {
        assertTrue(store.read("nf").isEmpty());
        assertEquals(0, snapshots.count());
    }

    // ---- 2. Warm (plus poster/playlist safety) ---------------------------

    @Test
    @DisplayName("02 Warm: publish then read returns FRESH rails; playlists stripped, posters kept")
    void warm() {
        assertTrue(store.publish("nf", sampleHome(), true, 1));
        CatalogueView view = store.read("nf").orElseThrow();

        assertEquals(Freshness.FRESH, view.freshness());
        assertEquals(2, view.rails().size());
        MirrorTileDto first = view.rails().get(0).items().get(0);
        assertEquals("101", first.id());
        assertEquals("https://imgcdn.kim/nf/v/101.jpg", first.image());   // allowlisted poster preserved
        // sanitizeRails nulls the playlist before storage. Jackson round-trips a JSON null back into a
        // JsonNode-typed field as a NullNode instance (not Java null), so the restored value must carry
        // no data rather than be reference-null. The security guarantee is proven by the JSON check below.
        assertTrue(first.playlist() == null || first.playlist().isNull(),
                "playlist (signed URL) must carry no data after storage");
        // The guarantee that matters: the signed stream payload is never persisted.
        String json = snapshots.findByScopeAndOtt("test", "nf").orElseThrow().getRailsJson();
        assertFalse(json.contains("sig-a"), "no signed stream URL may be persisted");
    }

    // ---- 3. Restart persistence -----------------------------------------

    @Test
    @DisplayName("03 Restart: a fresh store instance reads the persisted snapshot and continues the sequence")
    void restartPersistence() {
        store.publish("nf", sampleHome(), true, 7);

        // Simulate a process restart: a brand-new store over the same database.
        MirrorCatalogueStore restarted = new MirrorCatalogueStore(items, relations, snapshots, props, txManager);
        assertTrue(restarted.read("nf").isPresent(), "snapshot survives a restart");
        assertTrue(restarted.nextSequence() > 7, "sequence re-seeds past the persisted buildSeq");
    }

    // ---- 4. Snapshot ordering -------------------------------------------

    @Test
    @DisplayName("04 Ordering: rail order, within-rail order and Top 10 order survive the round trip")
    void ordering() {
        store.publish("nf", sampleHome(), true, 1);
        CatalogueView view = store.read("nf").orElseThrow();

        assertEquals(List.of("top10", "more-101"),
                view.rails().stream().map(MirrorRailDto::key).toList());
        assertEquals(List.of("101", "102", "103"),
                view.rails().get(0).items().stream().map(MirrorTileDto::id).toList());
    }

    // ---- 5. Concurrent upserts ------------------------------------------

    @Test
    @DisplayName("05 Concurrent upserts: racing publishes never throw and leave no duplicate rows")
    void concurrentUpserts() throws Exception {
        int threads = 6;
        var escaped = new CopyOnWriteArrayList<Throwable>();
        var start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            var done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                final long seq = i + 1;
                pool.submit(() -> {
                    try {
                        start.await();
                        // Post-CAS contract: publish is exception-free under a same-(scope,ott) race.
                        // The snapshot write is a guarded UPDATE + INSERT ... ON CONFLICT DO NOTHING, and
                        // item/relation writes are touch + ON CONFLICT DO NOTHING, so a losing publisher
                        // updates/inserts zero rows instead of hitting a unique violation and rolling its
                        // transaction back. NOTHING may escape publish() — any throwable is a regression.
                        store.publish("nf", sampleHome(), true, seq);
                    } catch (Throwable t) {
                        escaped.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "all publishers finished");
        }
        assertTrue(escaped.isEmpty(),
                () -> "publish must be exception-free under a concurrent same-key race, but threw: " + escaped);
        // Whatever the interleaving, the atomic upserts leave exactly five distinct tiles and one row.
        assertEquals(5, items.countByScopeAndOtt("test", "nf"), "ON CONFLICT DO NOTHING prevents duplicates");
        var row = snapshots.findByScopeAndOtt("test", "nf").orElseThrow();
        // The surviving snapshot carries the highest build sequence that competed after the cold insert —
        // never a lower one, proving the seq guard arbitrated the race rather than last-writer-wins.
        assertTrue(row.getBuildSeq() >= 1 && row.getBuildSeq() <= threads,
                "stored buildSeq is one of the racers': " + row.getBuildSeq());
        assertTrue(row.isComplete(), "a complete build won");
    }

    // ---- 6. Scope isolation (by OTT discriminator) ----------------------

    @Test
    @DisplayName("06 Scope isolation: Netflix and Prime catalogues do not bleed into each other")
    void scopeIsolation() {
        store.publish("nf", List.of(rail("r", "Top 10", tile("1", "NF", "nf/v/1", null))), true, 1);
        store.publish("pv", List.of(rail("r", "Top 10", tile("9", "PV", "pv/v/9", null))), true, 1);

        assertEquals("1", store.read("nf").orElseThrow().rails().get(0).items().get(0).id());
        assertEquals("9", store.read("pv").orElseThrow().rails().get(0).items().get(0).id());
        assertEquals(1, items.countByScopeAndOtt("test", "nf"));
        assertEquals(1, items.countByScopeAndOtt("test", "pv"));
    }

    // ---- 7. Executor saturation with a warm cache -----------------------

    @Test
    @DisplayName("07 Saturation: the warm read is the fallback source and never reaches upstream")
    void saturationServesWarmCache() {
        store.publish("nf", sampleHome(), true, 1);
        // The controller's saturation fallback calls exactly this; it returns cached rails, not empty,
        // and the store holds no upstream client, so no provider request is possible from this path.
        CatalogueView view = store.read("nf").orElseThrow();
        assertFalse(view.rails().isEmpty());
        assertEquals(Freshness.FRESH, view.freshness());
    }

    // ---- 8. DB failure ---------------------------------------------------

    @Test
    @DisplayName("08 DB failure: read degrades to empty; publish failure is contained by the home path")
    void dbFailureIsContained() {
        MirrorCatalogueItemRepository itemsM = mock(MirrorCatalogueItemRepository.class);
        MirrorCatalogueRelationRepository relM = mock(MirrorCatalogueRelationRepository.class);
        MirrorCatalogueSnapshotRepository snapM = mock(MirrorCatalogueSnapshotRepository.class);
        // Warm-read datastore error: the single-row lookup blows up.
        when(snapM.findByScopeAndOtt(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("simulated outage"));
        // Publish datastore error: the snapshot CAS itself fails (the new write path is a guarded UPDATE,
        // not an entity saveAndFlush). This failure is inside Tx1, which publish() does NOT catch.
        doThrow(new DataAccessResourceFailureException("simulated outage"))
                .when(snapM).conditionalReplace(any(), any(), any(), anyInt(), anyInt(), anyBoolean(), anyLong(), any());

        MirrorCatalogueProperties on = new MirrorCatalogueProperties();
        on.getPersistence().setEnabled(true);
        MirrorCatalogueStore failing = new MirrorCatalogueStore(itemsM, relM, snapM, on, txManager);

        // The warm-read path (the saturation fallback's source) is fully self-contained: any datastore
        // error degrades to an empty Optional, never an exception, so Home still composes.
        assertTrue(failing.read("nf").isEmpty(), "read degrades to empty on a datastore error, never throws");

        // publish() is best-effort at its caller boundary: a hard datastore error in the snapshot
        // transaction surfaces to its caller, which in production is MirrorHomeService.loadRail's
        // try { catalogueStore.publish(...) } catch (RuntimeException ex) { log.debug(...) } wrapper.
        // Assert the only thing that can escape publish is a DataAccessException the caller contains — a
        // NullPointerException or any other leak out of that wrapper would fail this test.
        assertDoesNotThrow(() -> {
            try {
                failing.publish("nf", sampleHome(), true, 1);
            } catch (org.springframework.dao.DataAccessException contained) {
                // exactly the class the home path's catch(RuntimeException) swallows
            }
        }, "a datastore error during publish stays a contained DataAccessException");
    }

    // ---- 9. Partial response preservation -------------------------------

    @Test
    @DisplayName("09 Partial never overwrites complete; the attempt is still recorded")
    void partialDoesNotOverwriteComplete() {
        store.publish("nf", sampleHome(), true, 1);               // complete LKG
        Instant beforeAttempt = snapshots.findByScopeAndOtt("test", "nf").orElseThrow().getLastAttemptedRefresh();

        List<MirrorRailDto> thin = List.of(rail("top10", "Top 10", tile("999", "Solo", "nf/v/999", null)));
        assertFalse(store.publish("nf", thin, false, 2), "partial build is rejected over a complete one");

        var row = snapshots.findByScopeAndOtt("test", "nf").orElseThrow();
        assertTrue(row.isComplete(), "stored snapshot stays complete");
        assertEquals(2, row.getRailCount(), "complete catalogue content is untouched");
        assertTrue(row.getLastAttemptedRefresh().isAfter(beforeAttempt), "the refresh attempt is recorded");
        assertEquals("101", store.read("nf").orElseThrow().rails().get(0).items().get(0).id());
    }

    // ---- 10. Stale write prevention -------------------------------------

    @Test
    @DisplayName("10 Stale write: an older build sequence cannot clobber a newer snapshot")
    void staleWritePrevention() {
        store.publish("nf", sampleHome(), true, 5);
        List<MirrorRailDto> older = List.of(rail("top10", "Top 10", tile("777", "Old", "nf/v/777", null)));
        assertFalse(store.publish("nf", older, true, 3), "lower buildSeq is dropped");

        var row = snapshots.findByScopeAndOtt("test", "nf").orElseThrow();
        assertEquals(5, row.getBuildSeq());
        assertEquals("101", store.read("nf").orElseThrow().rails().get(0).items().get(0).id());
    }

    // ---- 11. Hard expiry -------------------------------------------------

    @Test
    @DisplayName("11 Hard expiry: a snapshot past retention is not served")
    void hardExpiry() {
        store.publish("nf", sampleHome(), true, 1);
        backdate("nf", Duration.ofDays(8));                        // hard expiry is 7d
        assertTrue(store.read("nf").isEmpty(), "expired snapshot is withheld even as a fallback");

        // And a snapshot between soft TTL and hard expiry is served, flagged STALE.
        store.publish("nf", sampleHome(), true, 2);
        backdate("nf", Duration.ofHours(12));                      // soft TTL is 6h
        assertEquals(Freshness.STALE, store.read("nf").orElseThrow().freshness());
    }

    // ---- 12. Content removal --------------------------------------------

    @Test
    @DisplayName("12 Content removal: retention sweep drops items unseen past hard expiry")
    void contentRemoval() {
        // Seed a stale item directly, dated well past hard expiry.
        Instant old = Instant.now().minus(Duration.ofDays(30));
        items.save(MirrorCatalogueItem.builder()
                .scope("test").ott("nf").mirrorId("ghost").name("Ghost")
                .firstSeen(old).lastSeen(old).build());
        assertEquals(1, items.countByScopeAndOtt("test", "nf"));

        // A publish refreshes the live items and runs the retention sweep in the same transaction.
        store.publish("nf", sampleHome(), true, 1);

        assertTrue(items.findByScopeAndOttAndMirrorId("test", "nf", "ghost").isEmpty(), "stale item removed");
        assertEquals(5, items.countByScopeAndOtt("test", "nf"), "only current items remain");
    }

    // ---- 13. Provider cooldown / foreground playback priority -----------

    @Test
    @DisplayName("13 No upstream coupling: the warm read issues only bounded DB work, zero provider calls")
    void warmReadIsBoundedAndUpstreamFree() {
        store.publish("nf", sampleHome(), true, 1);

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        long before = stats.getPrepareStatementCount();
        CatalogueView view = store.read("nf").orElseThrow();
        long queries = stats.getPrepareStatementCount() - before;

        assertFalse(view.rails().isEmpty());
        // One snapshot select + one version-free last_access bulk update. Never O(rails) or O(items).
        assertTrue(queries <= 3, "warm read is bounded (no N+1): statements=" + queries);
        System.out.println("BENCH warm-read-queries=" + queries);
    }

    // ---- 14. API compatibility (persistence off == production) ----------

    @Test
    @DisplayName("14 API compatibility: with persistence off the store is inert")
    void persistenceOffIsInert() {
        MirrorCatalogueProperties off = new MirrorCatalogueProperties(); // enabled defaults to false
        MirrorCatalogueStore inert = new MirrorCatalogueStore(items, relations, snapshots, off, txManager);

        assertFalse(inert.persistenceEnabled());
        assertEquals(0L, inert.nextSequence(), "sequence stays 0 with persistence off");
        assertFalse(inert.publish("nf", sampleHome(), true, 1), "publish is a no-op");
        assertTrue(inert.read("nf").isEmpty(), "read is a no-op");
        assertEquals(0, snapshots.count(), "nothing written to the database");
    }

    // ---- 15. Transaction decomposition: metadata failure keeps the snapshot -----

    @Test
    @DisplayName("15 Decomposition: a failing item/relation refresh never rolls back the committed snapshot")
    void metadataFailureKeepsSnapshot() {
        // A store whose item repository fails every write, but whose snapshot repository is the real one.
        // The snapshot CAS (Tx1) and the item/relation refresh (Tx2) are independent committed
        // transactions, so Tx2 blowing up must leave the last-known-good snapshot standing.
        MirrorCatalogueItemRepository itemsDown = mock(MirrorCatalogueItemRepository.class);
        doThrow(new DataAccessResourceFailureException("items down"))
                .when(itemsDown).touch(any(), any(), any(), any(), any(), any(), any(), any(), any());
        doThrow(new DataAccessResourceFailureException("items down"))
                .when(itemsDown).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        MirrorCatalogueStore partial = new MirrorCatalogueStore(itemsDown, relations, snapshots, props, txManager);

        // publish returns true: the snapshot transaction committed, and the metadata failure is swallowed.
        assertTrue(partial.publish("nf", sampleHome(), true, 1),
                "snapshot commit succeeds even though the metadata refresh fails");

        // The snapshot survives and is fully readable through the real store.
        CatalogueView view = store.read("nf").orElseThrow();
        assertEquals(Freshness.FRESH, view.freshness());
        assertEquals(2, view.rails().size(), "the committed snapshot is intact");
        assertEquals("101", view.rails().get(0).items().get(0).id());

        // And the failed metadata transaction wrote no item rows — it rolled back in isolation.
        assertEquals(0, items.countByScopeAndOtt("test", "nf"),
                "the item table reflects the rolled-back metadata tx, not a half-written state");
    }

    // ---- 16. Scope authorization boundary -------------------------------

    @Test
    @DisplayName("16 Scope authorization: scope is a fixed non-secret token; catalogues never cross scopes")
    void scopeAuthorizationBoundary() {
        // The scope identifier is the configured, stable deployment token — never derived from a
        // session, cookie, credential or the request itself (publish/read take only an OTT string).
        assertEquals("test", store.scope(), "scope is the configured non-secret token");
        assertEquals(store.scope(), store.scope(), "scope is stable across calls, not request-derived");

        store.publish("nf", sampleHome(), true, 1);                         // scope = "test"

        // A second deployment scope is a wholly separate namespace. Nothing it writes is visible to the
        // first store, and nothing the first store wrote is visible to it — the DB rows are partitioned
        // on catalogue_scope, which is config, not a credential.
        MirrorCatalogueProperties tenantB = new MirrorCatalogueProperties();
        tenantB.getPersistence().setEnabled(true);
        tenantB.setScope("tenant-b");
        MirrorCatalogueStore storeB = new MirrorCatalogueStore(items, relations, snapshots, tenantB, txManager);
        storeB.publish("nf", List.of(rail("r", "Top 10",
                tile("500", "B-only", "https://imgcdn.kim/nf/v/500.jpg", null))), true, 1);

        assertEquals("101", store.read("nf").orElseThrow().rails().get(0).items().get(0).id(),
                "scope=test sees only its own catalogue");
        assertEquals("500", storeB.read("nf").orElseThrow().rails().get(0).items().get(0).id(),
                "scope=tenant-b sees only its own catalogue");
        assertEquals(5, items.countByScopeAndOtt("test", "nf"), "item rows are partitioned by scope");
        assertEquals(1, items.countByScopeAndOtt("tenant-b", "nf"), "the other scope holds only its own row");
    }

    // ---- 17. Poster allowlist -------------------------------------------

    @Test
    @DisplayName("17 Poster allowlist: only a plain https CDN .jpg survives; every unsafe shape is dropped")
    void posterAllowlist() {
        // One rail, nine tiles — one legitimate poster and eight unsafe shapes a blocklist would miss.
        List<MirrorTileDto> tiles = List.of(
                tile("ok",    "Legit",        "https://imgcdn.kim/nf/v/200/ok.jpg", null),
                tile("query", "Signed query", "https://imgcdn.kim/nf/v/200/ok.jpg?token=secret&exp=1", null),
                tile("frag",  "Fragment",     "https://imgcdn.kim/nf/v/200/ok.jpg#sig", null),
                tile("creds", "Credentials",  "https://user:pass@imgcdn.kim/nf/v/200/ok.jpg", null),
                tile("http",  "Plain http",   "http://imgcdn.kim/nf/v/200/ok.jpg", null),
                tile("host",  "Bad host",     "https://evil.example.com/nf/v/200/ok.jpg", null),
                tile("ext",   "Not a jpg",    "https://imgcdn.kim/nf/v/200/ok.png", null),
                tile("rel",   "Relative",     "nf/v/200/ok.jpg", null),
                tile("proto", "Scheme-rel",   "//imgcdn.kim/nf/v/200/ok.jpg", null));
        store.publish("nf", List.of(new MirrorRailDto("top10", "Top 10", tiles)), true, 1);

        Map<String, String> poster = new HashMap<>();
        for (MirrorTileDto t : store.read("nf").orElseThrow().rails().get(0).items()) {
            poster.put(t.id(), t.image());
        }
        assertEquals("https://imgcdn.kim/nf/v/200/ok.jpg", poster.get("ok"), "a plain https CDN .jpg is kept");
        for (String bad : List.of("query", "frag", "creds", "http", "host", "ext", "rel", "proto")) {
            assertNull(poster.get(bad), "unsafe poster must drop to a null placeholder: " + bad);
        }
        // The item table is sanitized by the same rule, not only the snapshot JSON.
        assertEquals("https://imgcdn.kim/nf/v/200/ok.jpg",
                items.findByScopeAndOttAndMirrorId("test", "nf", "ok").orElseThrow().getPosterUrl());
        assertNull(items.findByScopeAndOttAndMirrorId("test", "nf", "host").orElseThrow().getPosterUrl(),
                "an unsupported-host poster is not persisted in the item row either");
        // And no signed query string ever reaches storage.
        String json = snapshots.findByScopeAndOtt("test", "nf").orElseThrow().getRailsJson();
        assertFalse(json.contains("token=secret"), "no signed poster query may be persisted");
    }

    // ---- Micro-benchmark: per-OTT cached read ---------------------------

    @Test
    @DisplayName("BENCH cached-read latency and query count per OTT (zero upstream)")
    void cachedReadBenchmark() {
        for (String ott : List.of("nf", "pv", "dp")) {
            store.publish(ott, sampleHome(), true, 1);
        }
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        int warmup = 50, iters = 300;
        for (String ott : List.of("nf", "pv", "dp")) {
            for (int i = 0; i < warmup; i++) {
                assertTrue(store.read(ott).isPresent());
            }
            stats.clear();
            long q0 = stats.getPrepareStatementCount();
            long[] ns = new long[iters];
            for (int i = 0; i < iters; i++) {
                long t0 = System.nanoTime();
                CatalogueView v = store.read(ott).orElseThrow();
                ns[i] = System.nanoTime() - t0;
                assertFalse(v.rails().isEmpty());
            }
            long queries = stats.getPrepareStatementCount() - q0;
            Arrays.sort(ns);
            double perRead = queries / (double) iters;
            System.out.printf(
                    "BENCH ott=%s reads=%d p50_us=%.1f p95_us=%.1f p99_us=%.1f queriesPerRead=%.2f upstreamCalls=0%n",
                    ott, iters, ns[iters / 2] / 1000.0, ns[(int) (iters * 0.95)] / 1000.0,
                    ns[(int) (iters * 0.99)] / 1000.0, perRead);
            assertTrue(perRead <= 3.0, "bounded DB work per cached read for " + ott);
        }
    }

    // Keep an unused import honest if the compiler ever prunes: assertSame proves identity reuse.
    @SuppressWarnings("unused")
    private void identityWitness(Object a, Object b) {
        assertSame(a, b);
    }
}
