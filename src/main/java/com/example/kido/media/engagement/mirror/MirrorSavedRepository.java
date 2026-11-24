package com.example.kido.media.engagement.mirror;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MirrorSavedRepository extends JpaRepository<MirrorSavedItem, String> {

    boolean existsByProfileIdAndMirrorId(String profileId, String mirrorId);

    void deleteByProfileIdAndMirrorId(String profileId, String mirrorId);

    /**
     * This profile's list, newest save first.
     *
     * <p>The id breaks the tie on {@code createdAt}, for the reason the comment feed's
     * finder spells out: the clock behind {@code Instant.now()} advances in ticks, so two
     * titles saved in the same breath share a timestamp, and without a second fixed column
     * paging over them could repeat one and drop another.
     */
    Page<MirrorSavedItem> findByProfileIdOrderByCreatedAtDescIdDesc(String profileId, Pageable pageable);

    /**
     * Which of these mirror titles this profile has saved, as one query.
     *
     * <p>A rail of mirror posters marks its saved tiles the same way it marks its liked
     * ones — one round trip for the whole page, not one per tile. Returns ids only, since
     * that is all a bookmark glyph needs.
     */
    @Query("""
            select s.mirrorId from MirrorSavedItem s
            where s.profileId = :profileId and s.mirrorId in :mirrorIds
            """)
    List<String> findSavedMirrorIds(@Param("profileId") String profileId,
                                    @Param("mirrorIds") Collection<String> mirrorIds);
}
