package com.example.kido.media.session;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Aggregation is done in Java over a bounded window rather than with SQL date
 * functions, which differ between H2 and PostgreSQL. A home server's week of events
 * is a few thousand rows at most, so the portability is worth more than the push-down.
 */
public interface WatchEventRepository extends JpaRepository<WatchEvent, String> {

    List<WatchEvent> findByOccurredAtGreaterThanEqual(Instant since);

    List<WatchEvent> findByProfileIdAndOccurredAtGreaterThanEqual(String profileId, Instant since);

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

    /** Housekeeping: events older than the retention window are not worth keeping. */
    void deleteByOccurredAtLessThan(Instant cutoff);
}
