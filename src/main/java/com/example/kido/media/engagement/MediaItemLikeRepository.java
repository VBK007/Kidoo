package com.example.kido.media.engagement;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaItemLikeRepository extends JpaRepository<MediaItemLike, String> {

    boolean existsByProfileIdAndMediaItemId(String profileId, String mediaItemId);

    void deleteByProfileIdAndMediaItemId(String profileId, String mediaItemId);

    long countByMediaItemId(String mediaItemId);

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
}
