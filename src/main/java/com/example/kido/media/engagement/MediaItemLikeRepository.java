package com.example.kido.media.engagement;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.kido.media.catalog.MediaType;

public interface MediaItemLikeRepository extends JpaRepository<MediaItemLike, String> {

    boolean existsByProfileIdAndMediaItemId(String profileId, String mediaItemId);

    void deleteByProfileIdAndMediaItemId(String profileId, String mediaItemId);

    /** Every like for an item, e.g. when the item itself is being purged. */
    void deleteByMediaItemId(String mediaItemId);

    long countByMediaItemId(String mediaItemId);

    /**
     * Everything this profile has ever liked — the input to its taste model.
     *
     * <p>Named apart from the batched lookup below rather than overloading it: one
     * answers "which of these did they like", the other "what do they like", and those
     * read the same at a call site while meaning very different things.
     */
    @Query("select l.mediaItemId from MediaItemLike l where l.profileId = :profileId")
    List<String> findAllLikedItemIds(@Param("profileId") String profileId);

    /**
     * Which of these items this profile has liked.
     *
     * <p>One query per listing rather than one per tile: a grid of 40 posters would
     * otherwise cost 40 round trips just to decide which hearts are filled. Returns ids
     * only, since that is all the caller marks up.
     */
    @Query("""
            select l.mediaItemId from MediaItemLike l
            where l.profileId = :profileId and l.mediaItemId in :itemIds
            """)
    List<String> findLikedItemIds(@Param("profileId") String profileId,
                                  @Param("itemIds") Collection<String> itemIds);

    /**
     * Fresh likes per item, for a "this week" ranking — {@link #countByMediaItemId} is
     * the lifetime total {@code MediaItem.likeCount} mirrors, which says nothing about
     * whether that count was earned last year or this afternoon.
     *
     * <p>The type/visibility filter is a subquery for the same reason {@code
     * WatchEventRepository#topItemsByWatchTime} uses one: a like row holds a bare item
     * id and no association to filter through.
     */
    @Query("""
            select l.mediaItemId, count(l) from MediaItemLike l
            where l.mediaItemId in (
                select m.id from MediaItem m
                where m.missing = false and m.hidden = false and m.type in :types)
              and l.createdAt >= :since
            group by l.mediaItemId
            """)
    List<Object[]> countsByItemSince(@Param("types") List<MediaType> types, @Param("since") Instant since);
}
