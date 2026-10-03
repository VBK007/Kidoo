package com.example.kido.site;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SiteVisitRepository extends JpaRepository<SiteVisit, String> {

    long countByCreatedAtGreaterThanEqual(Instant since);

    /** Day-bucketing happens in the service, in Java — kept out of SQL so this reads
     * the same on Postgres and H2 rather than leaning on a dialect-specific date
     * function. A traffic count is never large enough for that to cost anything. */
    @Query("select v.createdAt from SiteVisit v where v.createdAt >= :since")
    List<Instant> findCreatedAtSince(@Param("since") Instant since);

    @Query("""
            select v.path, count(v) from SiteVisit v
            where v.createdAt >= :since
            group by v.path
            order by count(v) desc
            """)
    List<Object[]> topPathsSince(@Param("since") Instant since, Pageable pageable);

    @Query("""
            select v.referrer, count(v) from SiteVisit v
            where v.createdAt >= :since and v.referrer is not null
            group by v.referrer
            order by count(v) desc
            """)
    List<Object[]> topReferrersSince(@Param("since") Instant since, Pageable pageable);

    @Query("""
            select v.deviceType, count(v) from SiteVisit v
            where v.createdAt >= :since and v.deviceType is not null
            group by v.deviceType
            order by count(v) desc
            """)
    List<Object[]> topDeviceTypesSince(@Param("since") Instant since, Pageable pageable);

    @Query("""
            select v.browser, count(v) from SiteVisit v
            where v.createdAt >= :since and v.browser is not null
            group by v.browser
            order by count(v) desc
            """)
    List<Object[]> topBrowsersSince(@Param("since") Instant since, Pageable pageable);

    @Query("""
            select v.os, count(v) from SiteVisit v
            where v.createdAt >= :since and v.os is not null
            group by v.os
            order by count(v) desc
            """)
    List<Object[]> topOperatingSystemsSince(@Param("since") Instant since, Pageable pageable);

    List<SiteVisit> findTop50ByOrderByCreatedAtDesc();
}
