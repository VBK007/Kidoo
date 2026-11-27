package com.example.kido.mymirror.catalogue;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MirrorCatalogueRelationRepository extends JpaRepository<MirrorCatalogueRelation, String> {

    List<MirrorCatalogueRelation> findByScopeAndOttAndSourceRefOrderByOrdinalAsc(
            String scope, String ott, String sourceRef);

    long countByScopeAndOtt(String scope, String ott);

    /**
     * Replace a source's edges atomically: the caller deletes then re-inserts the whole set within
     * one transaction, so a recommendation rail is never left half-written.
     */
    @Modifying
    @Query("delete from MirrorCatalogueRelation r where r.scope = :scope and r.ott = :ott and r.sourceRef = :sourceRef")
    int deleteForSource(@Param("scope") String scope, @Param("ott") String ott, @Param("sourceRef") String sourceRef);

    /**
     * Insert one recommendation edge only if that exact {@code (scope, ott, source_ref, related_id,
     * kind)} edge is not present yet. {@code ON CONFLICT DO NOTHING} (conflict-target-less) makes the
     * write idempotent and race-safe: re-publishing the same rail, or two publishers inserting the
     * same edge concurrently, inserts zero extra rows and never throws — so a recommendation rail can
     * be rebuilt without ever poisoning the surrounding transaction. Native SQL for portability; the
     * id is a caller-supplied UUID.
     */
    @Modifying
    @Query(value = """
            insert into mirror_catalogue_relation
                (id, catalogue_scope, ott, source_ref, related_id, kind, ordinal, updated_at)
            values (:id, :scope, :ott, :sourceRef, :relatedId, :kind, :ordinal, :now)
            on conflict do nothing""",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") String id, @Param("scope") String scope, @Param("ott") String ott,
                       @Param("sourceRef") String sourceRef, @Param("relatedId") String relatedId,
                       @Param("kind") String kind, @Param("ordinal") int ordinal, @Param("now") Instant now);
    @Modifying
    @Query("delete from MirrorCatalogueRelation r where r.scope = :scope and r.ott = :ott")
    int deleteNamespace(@Param("scope") String scope, @Param("ott") String ott);
}
