package com.example.kido.mymirror.catalogue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MirrorCatalogueItemRepository extends JpaRepository<MirrorCatalogueItem, String> {

    /** The one row for this scoped tile, so a re-seen title updates rather than piles up. */
    Optional<MirrorCatalogueItem> findByScopeAndOttAndMirrorId(String scope, String ott, String mirrorId);

    List<MirrorCatalogueItem> findByScopeAndOtt(String scope, String ott);

    long countByScopeAndOtt(String scope, String ott);

    /**
     * Touch an existing scoped tile in place — a concurrency-safe update keyed on the unique
     * constraint, never a find-then-insert. A zero return means the row is not there yet and the
     * caller should insert.
     */
    @Modifying
    @Query("""
            update MirrorCatalogueItem i
               set i.name = :name, i.posterUrl = :posterUrl, i.genre = :genre,
                   i.year = :year, i.type = :type, i.lastSeen = :seen
             where i.scope = :scope and i.ott = :ott and i.mirrorId = :mirrorId""")
    int touch(@Param("scope") String scope, @Param("ott") String ott, @Param("mirrorId") String mirrorId,
              @Param("name") String name, @Param("posterUrl") String posterUrl, @Param("genre") String genre,
              @Param("year") Integer year, @Param("type") String type, @Param("seen") Instant seen);

    /** Content removal / retention: drop tiles in a scope not seen since the cutoff. */
    @Modifying
    @Query("delete from MirrorCatalogueItem i where i.scope = :scope and i.ott = :ott and i.lastSeen < :cutoff")
    int deleteStale(@Param("scope") String scope, @Param("ott") String ott, @Param("cutoff") Instant cutoff);
}
