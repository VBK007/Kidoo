package com.example.kido.media.engagement.mirror;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MirrorLikeRepository extends JpaRepository<MirrorLike, String> {

    boolean existsByProfileIdAndMirrorId(String profileId, String mirrorId);

    void deleteByProfileIdAndMirrorId(String profileId, String mirrorId);

    /**
     * The live total for a mirror title.
     *
     * <p>A mirror title has no {@code media_items} row to carry a denormalised
     * {@code likeCount}, so unlike the catalogue path this count is the total — computed
     * on demand over the unique {@code (profile_id, mirror_id)} index. It is cheap for the
     * handful of likes a single title gathers, and there is nowhere else for the number
     * to live.
     */
    long countByMirrorId(String mirrorId);

    /**
     * Which of these mirror titles this profile has liked.
     *
     * <p>One query per listing rather than one per tile, exactly as the catalogue path
     * does it: a rail of mirror posters would otherwise cost one round trip each to decide
     * which hearts are filled. Returns ids only, since that is all the caller marks up.
     */
    @Query("""
            select l.mirrorId from MirrorLike l
            where l.profileId = :profileId and l.mirrorId in :mirrorIds
            """)
    List<String> findLikedMirrorIds(@Param("profileId") String profileId,
                                    @Param("mirrorIds") Collection<String> mirrorIds);
}
