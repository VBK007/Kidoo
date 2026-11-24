package com.example.kido.media.engagement.mirror;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MirrorProgressRepository extends JpaRepository<MirrorProgress, String> {

    /** The one row for this title, so a new position overwrites rather than piles up. */
    Optional<MirrorProgress> findByProfileIdAndMirrorId(String profileId, String mirrorId);

    void deleteByProfileIdAndMirrorId(String profileId, String mirrorId);

    /**
     * This profile's Continue Watching rail — unfinished titles, most recently watched
     * first.
     *
     * <p>{@code finished = false} is the filter that empties the rail as titles are
     * completed, while their rows stay behind so a re-watch resumes from zero. The id
     * breaks the tie on {@code updatedAt} for the reason My List's finder does: the clock
     * ticks, so two titles touched in the same breath share a timestamp, and paging needs a
     * second fixed column or it repeats one row and drops another.
     */
    Page<MirrorProgress> findByProfileIdAndFinishedFalseOrderByUpdatedAtDescIdDesc(
            String profileId, Pageable pageable);
}
