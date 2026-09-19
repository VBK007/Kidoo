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
     * Every row a scan could no longer find on disk. Unbounded like {@link
     * #findByMissingFalse()} above — the admin purge action is the only caller, run by
     * hand rather than on a schedule, so a library large enough for this to matter is
     * also large enough that its owner would rather page through {@code /disk} first.
     */
    List<MediaItem> findByMissingTrue();

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

    List<MediaItem> findByTypeAndMissingFalseAndHiddenFalse(MediaType type);

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

    /**
     * Every language present in the library.
     *
     * <p>Serves the client's chip row, and — once there is a query layer above it — the
     * closed vocabulary a language filter validates against: a library can only be
     * searched for the languages it actually holds.
     */
    @Query("""
            select distinct l from MediaItem m join m.languages l
            where m.missing = false and m.hidden = false
            order by l
            """)
    List<String> findDistinctLanguages();

    // --- facet tallies, for collections the server discovers rather than is told ---
    //
    // One grouped query per facet instead of a count per value: a library with four
    // hundred distinct cast names would otherwise be four hundred round trips to decide
    // which of them deserve a collection. Each returns [value, count] ordered by count,
    // so the caller can take the top few and stop.

    @Query("""
            select g, count(m) from MediaItem m join m.genres g
            where m.missing = false and m.hidden = false and m.type in :types
            group by g
            order by count(m) desc, g asc
            """)
    List<Object[]> countByGenre(@Param("types") List<MediaType> types);

    @Query("""
            select p, count(m) from MediaItem m join m.people p
            where m.missing = false and m.hidden = false and m.type in :types
            group by p
            order by count(m) desc, p asc
            """)
    List<Object[]> countByPerson(@Param("types") List<MediaType> types);

    @Query("""
            select l, count(m) from MediaItem m join m.languages l
            where m.missing = false and m.hidden = false and m.type in :types
            group by l
            order by count(m) desc, l asc
            """)
    List<Object[]> countByLanguage(@Param("types") List<MediaType> types);

    /**
     * Per year, not per decade: bucketing is arithmetic on a column, and integer
     * division is one of the things H2 and PostgreSQL do not agree about. A library
     * spans a century at most, so the grouping into decades is cheaper done in Java
     * than it is to make portable in SQL.
     */
    @Query("""
            select m.year, count(m) from MediaItem m
            where m.missing = false and m.hidden = false and m.type in :types
              and m.year is not null
            group by m.year
            order by m.year asc
            """)
    List<Object[]> countByYear(@Param("types") List<MediaType> types);

    // --- music home screen: browse-by-facet rails ---
    //
    // Same facet-tally-then-fetch-top-N shape as genre/person/language above, scoped to
    // mood/activity/composer instead. Newest-first within a facet rather than any
    // ranking signal — these are browse shelves ("Feeling Romantic"), not judgements
    // about which track in that mood is best.

    @Query("""
            select m.mood, count(m) from MediaItem m
            where m.missing = false and m.hidden = false and m.type in :types
              and m.mood is not null
            group by m.mood
            order by count(m) desc, m.mood asc
            """)
    List<Object[]> countByMood(@Param("types") List<MediaType> types);

    @Query("""
            select m.activity, count(m) from MediaItem m
            where m.missing = false and m.hidden = false and m.type in :types
              and m.activity is not null
            group by m.activity
            order by count(m) desc, m.activity asc
            """)
    List<Object[]> countByActivity(@Param("types") List<MediaType> types);

    @Query("""
            select m.musicDirector, count(m) from MediaItem m
            where m.missing = false and m.hidden = false and m.type in :types
              and m.musicDirector is not null
            group by m.musicDirector
            order by count(m) desc, m.musicDirector asc
            """)
    List<Object[]> countByMusicDirector(@Param("types") List<MediaType> types);

    @Query("""
            select m from MediaItem m
            where m.missing = false and m.hidden = false and m.type in :types
              and m.mood = :mood
            order by m.addedAt desc, m.sortTitle asc
            """)
    List<MediaItem> findByMood(@Param("types") List<MediaType> types,
                              @Param("mood") String mood, Pageable pageable);

    @Query("""
            select m from MediaItem m
            where m.missing = false and m.hidden = false and m.type in :types
              and m.activity = :activity
            order by m.addedAt desc, m.sortTitle asc
            """)
    List<MediaItem> findByActivity(@Param("types") List<MediaType> types,
                                   @Param("activity") String activity, Pageable pageable);

    @Query("""
            select m from MediaItem m
            where m.missing = false and m.hidden = false and m.type in :types
              and m.musicDirector = :musicDirector
            order by m.addedAt desc, m.sortTitle asc
            """)
    List<MediaItem> findByMusicDirector(@Param("types") List<MediaType> types,
                                        @Param("musicDirector") String musicDirector, Pageable pageable);

    /**
     * Every credit line on the music in the library, unparsed.
     *
     * <p>Two raw columns rather than a {@code group by}, because neither holds one
     * name: {@code artist} is "A.R. Rahman,Shreya Ghoshal,Sarthak Kalyani" and
     * {@code castMembers} is a whole billing order. Grouping in SQL would count each
     * *combination* of people as though it were a person, so the splitting and
     * counting happen in Java where a comma means what it looks like.
     *
     * <p>Two columns and no entities: this reads every music row, and dragging whole
     * objects across for two strings is the difference between a query that is fine
     * and one that hurts on a large library.
     */
    @Query("""
            select m.artist, m.castMembers from MediaItem m
            where m.missing = false and m.hidden = false and m.type in :types
            """)
    List<Object[]> musicCredits(@Param("types") List<MediaType> types);

    /**
     * Rows whose credits mention a name anywhere.
     *
     * <p>Deliberately loose. Matching {@code ,name,} exactly in SQL founders on the
     * spacing real taggers use — "A.R.Rahman, Sunitha Sarathy" has a space after the
     * comma and "A.R. Rahman,Shreya Ghoshal" does not — so this narrows the rows and
     * the caller checks each one against properly split, trimmed names. Without that
     * second pass "Raja" would collect every Yuvan Shankar Raja track in the house.
     *
     * <p>Case-sensitive, and safely so: the only caller searches for names it read out
     * of these same columns, so the casing already matches. {@code lower()} is not an
     * option regardless — {@code castMembers} is mapped to a CLOB, and Hibernate will
     * not apply a string function to one.
     */
    @Query("""
            select m from MediaItem m
            where m.missing = false and m.hidden = false and m.type in :types
              and m.artist like concat('%', :name, '%')
            order by m.addedAt desc, m.sortTitle asc
            """)
    List<MediaItem> findByArtistMentioning(@Param("types") List<MediaType> types,
                                           @Param("name") String name, Pageable pageable);

    /** As {@link #findByArtistMentioning}, over the billing order instead. */
    @Query("""
            select m from MediaItem m
            where m.missing = false and m.hidden = false and m.type in :types
              and m.castMembers like concat('%', :name, '%')
            order by m.addedAt desc, m.sortTitle asc
            """)
    List<MediaItem> findByCastMentioning(@Param("types") List<MediaType> types,
                                         @Param("name") String name, Pageable pageable);

    @Query("""
            select m from MediaItem m
            where m.missing = false and m.hidden = false and m.type in :types
              and m.year between :startYear and :endYear
            order by m.addedAt desc, m.sortTitle asc
            """)
    List<MediaItem> findByYearBetween(@Param("types") List<MediaType> types,
                                      @Param("startYear") int startYear,
                                      @Param("endYear") int endYear, Pageable pageable);

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

    /**
     * The home-video timeline, newest shoot date first.
     *
     * <p>The id breaks ties for the same reason it does on a comment thread, and it
     * matters more here: a capture date is a day, not an instant, so a whole afternoon's
     * clips share one — and the client pages through this and merges groups as it
     * scrolls, so an order that differs between two pages shows one clip twice and drops
     * another.
     */
    Page<MediaItem> findByTypeInAndMissingFalseAndHiddenFalseOrderByCapturedAtDescIdDesc(
            List<MediaType> types, Pageable pageable);
}
