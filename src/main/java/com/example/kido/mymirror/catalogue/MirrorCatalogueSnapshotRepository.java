package com.example.kido.mymirror.catalogue;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MirrorCatalogueSnapshotRepository extends JpaRepository<MirrorCatalogueSnapshot, String> {

    /** The one LKG snapshot for a scoped OTT; the warm Home read's single-row lookup. */
    Optional<MirrorCatalogueSnapshot> findByScopeAndOtt(String scope, String ott);

    /** Seed the in-memory build sequence past anything already persisted, so it stays monotonic. */
    @Query("select coalesce(max(s.buildSeq), 0) from MirrorCatalogueSnapshot s")
    long maxBuildSeq();

    /**
     * Compare-and-set the snapshot for a scoped OTT in one atomic statement — the concurrency-safe
     * replacement for the old find-then-save-and-catch. The {@code WHERE} encodes both write guards,
     * so a losing publish updates <b>zero</b> rows (and never throws, never poisons the transaction):
     * <ul>
     *   <li>{@code :seq > build_seq} — an older or equal build can never overwrite a newer snapshot,
     *       which makes out-of-order and stale async publication safe;</li>
     *   <li>{@code (:complete = true OR complete = false)} — a partial build (:complete=false) can
     *       only land on a row that is itself not complete; a complete snapshot is never clobbered by
     *       a partial one.</li>
     * </ul>
     * A return of 1 means this build became the stored snapshot; 0 means either no row exists yet
     * (the caller then inserts) or this build was legitimately superseded. Native SQL so the exact
     * guarded UPDATE is portable across H2 and PostgreSQL. {@code version} is intentionally untouched:
     * the seq guard, not optimistic locking, is the arbiter now, and no entity-based save remains.
     */
    @Modifying
    @Query(value = """
            update mirror_catalogue_snapshot
               set rails_json = :json, complete = :complete, build_seq = :seq,
                   rail_count = :railCount, item_count = :itemCount,
                   last_successful_refresh = :now, last_attempted_refresh = :now
             where catalogue_scope = :scope and ott = :ott
               and :seq > build_seq and (:complete = true or complete = false)""",
            nativeQuery = true)
    int conditionalReplace(@Param("scope") String scope, @Param("ott") String ott,
                           @Param("json") String json, @Param("railCount") int railCount,
                           @Param("itemCount") int itemCount, @Param("complete") boolean complete,
                           @Param("seq") long seq, @Param("now") Instant now);

    /**
     * Insert a snapshot only if no row exists for the scoped OTT, atomically and without throwing on a
     * concurrent insert: {@code ON CONFLICT DO NOTHING} (the conflict-target-less form, the one shape
     * both H2 2.4.x and PostgreSQL accept) silently inserts zero rows on a unique-key collision. A
     * return of 1 means this build won the cold insert; 0 means a racing publisher got there first and
     * the caller re-runs {@link #conditionalReplace}. {@code version} seeds at 0; the row is never
     * updated through an entity afterwards, so it stays 0.
     */
    @Modifying
    @Query(value = """
            insert into mirror_catalogue_snapshot
                (id, catalogue_scope, ott, rails_json, complete, build_seq, rail_count, item_count,
                 last_successful_refresh, last_attempted_refresh, last_access, version)
            values (:id, :scope, :ott, :json, :complete, :seq, :railCount, :itemCount, :now, :now, :now, 0)
            on conflict do nothing""",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") String id, @Param("scope") String scope, @Param("ott") String ott,
                       @Param("json") String json, @Param("railCount") int railCount,
                       @Param("itemCount") int itemCount, @Param("complete") boolean complete,
                       @Param("seq") long seq, @Param("now") Instant now);

    /**
     * Record that the catalogue was last <i>served</i>, without touching the row's version or any
     * content — a bulk update, so a warm read never contends with a concurrent publish.
     */
    @Modifying
    @Query("update MirrorCatalogueSnapshot s set s.lastAccess = :at where s.scope = :scope and s.ott = :ott")
    int markAccessed(@Param("scope") String scope, @Param("ott") String ott, @Param("at") Instant at);

    /**
     * Record that a refresh was last <i>attempted</i> (success or not) without altering the stored
     * catalogue — used when a partial build is deliberately not allowed to overwrite a complete one.
     */
    @Modifying
    @Query("update MirrorCatalogueSnapshot s set s.lastAttemptedRefresh = :at where s.scope = :scope and s.ott = :ott")
    int markAttempted(@Param("scope") String scope, @Param("ott") String ott, @Param("at") Instant at);
}
