package com.example.kido.analytics;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginEventRepository extends JpaRepository<LoginEvent, String> {
    List<LoginEvent> findByUserIdOrderByAtDesc(String userId);

    // --- admin dashboard ---
    //
    // Sign-ins are the only record of an account being used at all: playback is per
    // profile, and plenty of what the apps do never starts a stream. Counting distinct
    // accounts over a window is therefore what "active users" can honestly mean here.

    @Query("select count(distinct e.userId) from LoginEvent e where e.at >= :since")
    long countDistinctUsersSince(@Param("since") Instant since);

    /**
     * Per-application totals over all time, newest sign-in included.
     *
     * <p>Grouped on the raw platform column, nulls and all: a build that declares
     * nothing is still an app someone is using, and the caller labels that row rather
     * than dropping it. Someone who signs in from two apps is counted in both rows,
     * so these deliberately do not sum to the account total.
     *
     * @return {@code [platform, distinctUsers, logins, lastSeenAt]} rows
     */
    @Query("""
            select e.platform, count(distinct e.userId), count(e), max(e.at)
            from LoginEvent e
            group by e.platform
            """)
    List<Object[]> platformTotals();

    /**
     * Distinct accounts per application inside a window.
     *
     * @return {@code [platform, distinctUsers]} rows
     */
    @Query("""
            select e.platform, count(distinct e.userId) from LoginEvent e
            where e.at >= :since
            group by e.platform
            """)
    List<Object[]> activeUsersByPlatformSince(@Param("since") Instant since);

    /**
     * Sign-ins per application inside a window — the volume behind the user count, so
     * an app used daily by five people reads differently from one opened once by five.
     *
     * @return {@code [platform, logins]} rows
     */
    @Query("""
            select e.platform, count(e) from LoginEvent e
            where e.at >= :since
            group by e.platform
            """)
    List<Object[]> loginsByPlatformSince(@Param("since") Instant since);
}
