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
