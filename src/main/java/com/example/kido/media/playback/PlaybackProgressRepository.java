package com.example.kido.media.playback;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaybackProgressRepository extends JpaRepository<PlaybackProgress, String> {

    Optional<PlaybackProgress> findByProfileIdAndMediaItemId(String profileId, String mediaItemId);

    /** Batched so a catalog page can be decorated with resume state in one query. */
    List<PlaybackProgress> findByProfileIdAndMediaItemIdIn(
            String profileId, Collection<String> mediaItemIds);

    /**
     * The continue-watching row: started, not finished, most recent first. Paged
     * because the client renders only the first handful.
     */
    List<PlaybackProgress> findByProfileIdAndWatchedFalseAndPositionSecondsGreaterThanOrderByUpdatedAtDesc(
            String profileId, double minPosition, Pageable pageable);

    void deleteByProfileIdAndMediaItemId(String profileId, String mediaItemId);

    /** Watch hours per day, for the admin panel's 7-day chart. */
    @Query("""
            select p.profileId, sum(p.positionSeconds) from PlaybackProgress p
            where p.updatedAt >= :since
            group by p.profileId
            """)
    List<Object[]> secondsWatchedByProfileSince(@Param("since") java.time.Instant since);

    /** Items nobody has ever finished — the admin panel's "never watched" flag. */
    @Query("select distinct p.mediaItemId from PlaybackProgress p where p.watched = true")
    List<String> findWatchedItemIds();
}
