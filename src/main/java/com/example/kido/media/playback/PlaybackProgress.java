package com.example.kido.media.playback;

import java.time.Instant;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Where one user got to in one movie.
 *
 * <p>Stored per user rather than per device so resume works when someone moves from
 * the phone to a tablet. The movie is referenced by id rather than by a JPA
 * association: progress must survive a movie row being marked missing, and nothing
 * here needs to navigate to the movie itself.
 */
@Entity
@Table(name = "movie_playback_progress",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_progress_user_movie", columnNames = {"user_id", "movie_id"}),
        indexes = @Index(name = "idx_progress_user_updated", columnList = "user_id, updated_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaybackProgress {

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "movie_id", nullable = false)
    private String movieId;

    @Column(name = "position_seconds", nullable = false)
    private double positionSeconds;

    /** Copied from the client so "how far through" can be shown without a probe. */
    @Column(name = "duration_seconds")
    private Double durationSeconds;

    @Builder.Default
    private boolean watched = false;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /** Null when the duration is unknown. */
    public Integer percentComplete() {
        if (durationSeconds == null || durationSeconds <= 0) {
            return null;
        }
        int percent = (int) Math.round(positionSeconds / durationSeconds * 100.0);
        return Math.max(0, Math.min(100, percent));
    }
}
