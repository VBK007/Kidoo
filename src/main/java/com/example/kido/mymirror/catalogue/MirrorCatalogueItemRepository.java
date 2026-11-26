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

    /**
     * Insert a tile only if its scoped identity is not present yet — the concurrency-safe other half
     * of {@link #touch}. {@code ON CONFLICT DO NOTHING} (conflict-target-less, the one form both H2
     * 2.4.x and PostgreSQL accept) inserts zero rows on a unique-key collision instead of throwing, so
     * a same-key race between two publishers can never roll the transaction back. A zero return means a
     * racing insert won, and the caller re-runs {@link #touch} to fold this build's metadata in. Native
     * SQL for cross-dialect portability; the id is a caller-supplied UUID.
     */
    @Modifying
    @Query(value = """
            insert into mirror_catalogue_item
                (id, catalogue_scope, ott, mirror_id, name, poster_url, genre, release_year, type,
                 first_seen, last_seen)
            values (:id, :scope, :ott, :mirrorId, :name, :posterUrl, :genre, :year, :type, :now, :now)
            on conflict do nothing""",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") String id, @Param("scope") String scope, @Param("ott") String ott,
                       @Param("mirrorId") String mirrorId, @Param("name") String name,
                       @Param("posterUrl") String posterUrl, @Param("genre") String genre,
                       @Param("year") Integer year, @Param("type") String type, @Param("now") Instant now);

    /** Content removal / retention: drop tiles in a scope not seen since the cutoff. */
    @Modifying
    @Query("delete from MirrorCatalogueItem i where i.scope = :scope and i.ott = :ott and i.lastSeen < :cutoff")
    int deleteStale(@Param("scope") String scope, @Param("ott") String ott, @Param("cutoff") Instant cutoff);
}
