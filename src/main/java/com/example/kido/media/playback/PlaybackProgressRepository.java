package com.example.kido.media.playback;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaybackProgressRepository extends JpaRepository<PlaybackProgress, String> {

    Optional<PlaybackProgress> findByUserIdAndMovieId(String userId, String movieId);

    /** Batched so a catalog page can be decorated with resume state in one query. */
    List<PlaybackProgress> findByUserIdAndMovieIdIn(String userId, Collection<String> movieIds);

    /**
     * The "continue watching" row: started, not finished, most recent first.
     * Paged because the client only ever renders the first handful.
     */
    List<PlaybackProgress> findByUserIdAndWatchedFalseAndPositionSecondsGreaterThanOrderByUpdatedAtDesc(
            String userId, double minPosition, Pageable pageable);

    void deleteByUserIdAndMovieId(String userId, String movieId);
}
