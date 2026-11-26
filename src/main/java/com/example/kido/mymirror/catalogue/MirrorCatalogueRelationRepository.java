package com.example.kido.mymirror.catalogue;

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
}
