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
 * Where one profile got to in one item.
 *
 * <p>Keyed on profile rather than account: a household shares a login, but a resume
 * point is personal, and the kids-mode "keep going" card must not offer what a parent
 * was halfway through.
 *
 * <p>The item is referenced by id rather than a JPA association, so progress survives
 * an item being marked missing when a disk is unmounted.
 */
@Entity
@Table(name = "media_playback_progress",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_progress_profile_item", columnNames = {"profile_id", "media_item_id"}),
        indexes = @Index(name = "idx_progress_profile_updated",
                columnList = "profile_id, updated_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaybackProgress {

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "profile_id", nullable = false)
    private String profileId;

    @Column(name = "media_item_id", nullable = false)
    private String mediaItemId;

    @Column(name = "position_seconds", nullable = false)
    private double positionSeconds;

    /** Copied from the client so progress can be shown without probing the file. */
    @Column(name = "duration_seconds")
    private Double durationSeconds;

    @Builder.Default
    private boolean watched = false;

    /**
     * Subtitle sync offset in seconds for this profile and file, as set by the client's
     * offset tuner. Persisted per file because the mismatch is a property of the file.
     */
    @Column(name = "subtitle_offset_seconds")
    private Double subtitleOffsetSeconds;

    /** Index of the subtitle track last chosen, so playback resumes with it selected. */
    @Column(name = "subtitle_track_index")
    private Integer subtitleTrackIndex;

    /** Index of the audio track last chosen, for multi-audio files. */
    @Column(name = "audio_track_index")
    private Integer audioTrackIndex;

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
