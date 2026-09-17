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

    /** Every profile's progress on an item, e.g. when the item itself is being purged. */
    void deleteByMediaItemId(String mediaItemId);

    /**
     * Everything this profile has ever started.
     *
     * <p>Unbounded, and deliberately: it is the input to the taste model, which is a
     * statement about a person rather than a page of results. A household profile
     * accumulates hundreds of these, not millions â and the alternative, paging through
     * them to build one vector, would be slower for no benefit.
     */
    List<PlaybackProgress> findByProfileId(String profileId);

    /** Watch hours per day, for the admin panel's 7-day chart. */
    @Query("""
            select p.profileId, sum(p.positionSeconds) from PlaybackProgress p
            where p.updatedAt >= :since
            group by p.profileId
            """)
    List<Object[]> secondsWatchedByProfileSince(@Param("since") java.time.Instant since);

    /** Items at least one profile has finished — the "never watched" flag inverts it. */
    @Query("select distinct p.mediaItemId from PlaybackProgress p where p.watched = true")
    List<String> findWatchedItemIds();

    /** How many profiles have finished each item, for the "everyone has seen it" check. */
    @Query("""
            select p.mediaItemId, count(p) from PlaybackProgress p
            where p.watched = true
            group by p.mediaItemId
            """)
    List<Object[]> countWatchedByItem();
}
