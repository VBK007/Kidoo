package com.example.kido.media.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Browsing uses {@link JpaSpecificationExecutor} rather than derived queries: the
 * catalog filters are optional and combinable, and binding an unused {@code null}
 * parameter into a JPQL {@code :x is null} guard makes PostgreSQL fail to infer the
 * parameter type. Criteria queries omit the predicate instead.
 */
public interface MediaItemRepository
        extends JpaRepository<MediaItem, String>, JpaSpecificationExecutor<MediaItem> {

    Optional<MediaItem> findByFilePath(String filePath);

    /**
     * Every present row. Unbounded by nature, so callers that walk the whole library
     * must use the paged overload below instead of this one.
     */
    List<MediaItem> findByMissingFalse();

    /** Paged walk over the whole library, for scan-time reconciliation. */
    Page<MediaItem> findByMissingFalse(Pageable pageable);

    /**
     * Count and bytes of titles every profile has finished and nobody has touched
     * since {@code cutoff}.
     *
     * <p>An aggregate rather than a full load: this backs one line of the admin Disk
     * tab, and reading an entire library into memory to render it would be indefensible
     * once the library is large. The correlated subquery counts how many profiles have
     * finished each item, and coalesce treats a never-played item by when it was added.
     */
    @Query("""
            select count(m), coalesce(sum(m.fileSize), 0) from MediaItem m
            where m.missing = false
              and coalesce(m.lastPlayedAt, m.addedAt) < :cutoff
              and (select count(p) from PlaybackProgress p
                   where p.mediaItemId = m.id and p.watched = true) >= :profileCount
            """)
    List<Object[]> reclaimableStats(@Param("cutoff") Instant cutoff,
                                    @Param("profileCount") long profileCount);

    long countByMissingFalseAndHiddenFalse();

    long countByTypeAndMissingFalseAndHiddenFalse(MediaType type);

    /** Recently added rail on the client's home screen. */
    List<MediaItem> findByTypeInAndMissingFalseAndHiddenFalseOrderByAddedAtDesc(
            List<MediaType> types, Pageable pageable);

    // --- home-screen ranking ---
    //
    // Three separate top-N queries rather than one scored ORDER BY. The blended score
    // normalises each signal against the library's own maximum, which SQL cannot
    // express without a second pass, and the window functions that could differ
    // between H2 and PostgreSQL. Each of these is an indexed sort truncated by the
    // pageable, and the ranker unions and scores the small pool they return.

    /** Best-rated titles. Unrated items are excluded rather than sorted last. */
    @Query("""
            select m from MediaItem m
            where m.missing = false and m.hidden = false and m.rating is not null
              and m.type in :types
            order by m.rating desc, m.sortTitle asc
            """)
    List<MediaItem> findTopRated(@Param("types") List<MediaType> types, Pageable pageable);

    /**
     * Most-played titles, counting both ways a file can be served — how a title reached
     * the screen says nothing about how popular it is.
     */
    @Query("""
            select m from MediaItem m
            where m.missing = false and m.hidden = false and m.type in :types
              and (m.directPlayCount + m.transcodeCount) > 0
            order by (m.directPlayCount + m.transcodeCount) desc, m.sortTitle asc
            """)
    List<MediaItem> findMostPlayed(@Param("types") List<MediaType> types, Pageable pageable);

    @Query("""
            select m from MediaItem m
            where m.missing = false and m.hidden = false and m.type in :types
              and m.likeCount > 0
            order by m.likeCount desc, m.sortTitle asc
            """)
    List<MediaItem> findMostLiked(@Param("types") List<MediaType> types, Pageable pageable);

    /**
     * Library-wide mean rating, the prior an unrated title is scored with so that
     * having no sidecar rating neither rewards nor punishes it.
     */
    @Query("""
            select avg(m.rating) from MediaItem m
            where m.missing = false and m.hidden = false and m.rating is not null
              and m.type in :types
            """)
    Double averageRating(@Param("types") List<MediaType> types);

    @Query("""
            select distinct g from MediaItem m join m.genres g
            where m.missing = false and m.hidden = false
            order by g
            """)
    List<String> findDistinctGenres();

    @Query("""
            select distinct p from MediaItem m join m.people p
            where m.missing = false and m.hidden = false
            order by p
            """)
    List<String> findDistinctPeople();

    @Query("""
            select coalesce(sum(m.fileSize), 0) from MediaItem m
            where m.missing = false and m.hidden = false
            """)
    long totalBytes();

    /** Per-category totals for the client's library header and disk-usage bar. */
    @Query("""
            select m.type, count(m), coalesce(sum(m.fileSize), 0) from MediaItem m
            where m.missing = false and m.hidden = false
            group by m.type
            """)
    List<Object[]> countAndBytesByType();

    long countByMissingTrue();

    /** Items whose metadata is trustworthy, as candidate sources for fixing others. */
    List<MediaItem> findByMetadataSourceInAndMissingFalseAndHiddenFalse(
            List<MetadataSource> sources, Pageable pageable);

    /** Biggest files on disk, for the admin panel. */
    List<MediaItem> findByMissingFalseAndHiddenFalseOrderByFileSizeDesc(Pageable pageable);

    /** Items whose metadata was guessed from the filename, so likely mismatched. */
    long countByMetadataSourceAndMissingFalseAndHiddenFalse(MetadataSource source);

    List<MediaItem> findByMetadataSourceAndMissingFalseAndHiddenFalse(
            MetadataSource source, Pageable pageable);

    /** Files that have played but never once direct-played. */
    long countByTranscodeCountGreaterThanAndDirectPlayCountAndMissingFalse(
            long minTranscodes, long directPlays);

    List<MediaItem> findByTranscodeCountGreaterThanAndDirectPlayCountAndMissingFalseOrderByTranscodeCountDesc(
            long minTranscodes, long directPlays, Pageable pageable);

    /** Timeline items still lacking a capture date — the client tagging nudge. */
    long countByTypeInAndCapturedAtIsNullAndMissingFalseAndHiddenFalse(List<MediaType> types);

    Page<MediaItem> findByTypeInAndMissingFalseAndHiddenFalseOrderByCapturedAtDesc(
            List<MediaType> types, Pageable pageable);
}
