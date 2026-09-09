package com.example.kido.media.catalog;

import java.util.List;
import java.util.Optional;

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

    List<MediaItem> findByMissingFalse();

    long countByMissingFalseAndHiddenFalse();

    long countByTypeAndMissingFalseAndHiddenFalse(MediaType type);

    /** Recently added rail on the client's home screen. */
    List<MediaItem> findByTypeInAndMissingFalseAndHiddenFalseOrderByAddedAtDesc(
            List<MediaType> types, Pageable pageable);

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

    @Query("""
            select m from MediaItem m
            where m.missing = false and m.mediaInfo.probedAt is null
              and m.type in :types
            """)
    List<MediaItem> findUnprobed(@Param("types") List<MediaType> types);

    /** Timeline items still lacking a capture date — the client's tagging nudge. */
    long countByTypeInAndCapturedAtIsNullAndMissingFalseAndHiddenFalse(List<MediaType> types);

    List<MediaItem> findByTypeInAndMissingFalseAndHiddenFalseOrderByCapturedAtDesc(
            List<MediaType> types, Pageable pageable);
}
