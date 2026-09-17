package com.example.kido.media.session;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.kido.media.catalog.MediaType;

/**
 * Aggregation is done in Java over a bounded window rather than with SQL date
 * functions, which differ between H2 and PostgreSQL. A home server's week of events
 * is a few thousand rows at most, so the portability is worth more than the push-down.
 */
public interface WatchEventRepository extends JpaRepository<WatchEvent, String> {

    /**
     * Seconds watched inside one window, across everyone.
     *
     * <p>The seven-day chart asks this once per day rather than loading the week's
     * events and bucketing them in memory. Each call is an indexed range aggregate
     * returning a single number, where the in-memory version had to carry every row a
     * busy household produced — several thousand a week at one report every few
     * seconds. Seven cheap queries beat one large transfer, and it stays portable
     * across H2 and PostgreSQL, which disagree on date-truncation functions.
     */
    @Query("""
            select coalesce(sum(e.secondsWatched), 0) from WatchEvent e
            where e.occurredAt >= :from and e.occurredAt < :to
            """)
    double sumSecondsBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select coalesce(sum(e.secondsWatched), 0) from WatchEvent e
            where e.profileId = :profileId and e.occurredAt >= :from and e.occurredAt < :to
            """)
    double sumSecondsForProfileBetween(@Param("profileId") String profileId,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to);

    @Query("""
            select coalesce(sum(e.secondsWatched), 0) from WatchEvent e
            where e.occurredAt >= :since
            """)
    double totalSecondsSince(@Param("since") Instant since);

    @Query("""
            select e.profileId, coalesce(sum(e.secondsWatched), 0) from WatchEvent e
            where e.occurredAt >= :since
            group by e.profileId
            """)
    List<Object[]> secondsByProfileSince(@Param("since") Instant since);

    @Query("""
            select e.mediaItemId, coalesce(sum(e.secondsWatched), 0) from WatchEvent e
            group by e.mediaItemId
            """)
    List<Object[]> secondsByItem();

    /**
     * The titles the household has spent the most time on, most first.
     *
     * <p>Distinct from the play count the {@code most-watched} rail sorts on: that
     * counts how many times a file was started, so five minutes of a film someone
     * gave up on outranks another watched end to end. Summing the increments recorded
     * here answers the question a count of starts only approximates.
     *
     * <p>The type and visibility filter is a subquery rather than a join because a
     * watch event holds a bare item id and no association to the item. Filtering in
     * SQL rather than over-fetching and discarding afterwards means the pageable is an
     * exact page of eligible titles instead of a pool that might come back short. Ties
     * are broken by id so the rail does not reshuffle between two requests that see
     * the same data.
     *
     * @return {@code [mediaItemId, secondsWatched]} rows, longest-watched first
     */
    @Query("""
            select e.mediaItemId, sum(e.secondsWatched) from WatchEvent e
            where e.mediaItemId in (
                select m.id from MediaItem m
                where m.missing = false and m.hidden = false and m.type in :types)
            group by e.mediaItemId
            order by sum(e.secondsWatched) desc, e.mediaItemId asc
            """)
    List<Object[]> topItemsByWatchTime(@Param("types") List<MediaType> types,
                                       Pageable pageable);

    /**
     * Seconds this profile has spent on each title it has touched.
     *
     * <p>Per profile, unlike {@link #secondsByItem()}: taste is a statement about a
     * person, and one housemate watching something twice says nothing about another.
     */
    @Query("""
            select e.mediaItemId, coalesce(sum(e.secondsWatched), 0) from WatchEvent e
            where e.profileId = :profileId
            group by e.mediaItemId
            """)
    List<Object[]> secondsByItemForProfile(@Param("profileId") String profileId);

    /** Housekeeping: events older than the retention window are not worth keeping. */
    void deleteByOccurredAtLessThan(Instant cutoff);

    /** Every watch event for an item, e.g. when the item itself is being purged. */
    void deleteByMediaItemId(String mediaItemId);
}
