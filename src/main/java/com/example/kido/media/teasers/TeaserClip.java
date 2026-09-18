package com.example.kido.media.teasers;

import java.time.Instant;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A curated, vertical (9:16) preview clip cut from a movie's own source file.
 *
 * <p>Selection is a deliberate, admin-triggered action — start/end seconds and an
 * optional crop offset, usually from researching which moment in a film is worth
 * showing — never a fully automatic pipeline. Persisted like {@code DownloadJob}
 * because cutting and re-encoding runs on a background thread and a restart mid-encode
 * has to be recoverable rather than left claiming to be in progress forever.
 *
 * <p>Simpler than {@code DownloadJob} in two ways: no {@code CANCELLED}/{@code EXPIRED}
 * states, since a few seconds of source is not worth cancel/expiry machinery, and no
 * live progress percentage, since trimming+cropping this little normally finishes in
 * seconds even on a fast preset.
 */
@Entity
@Table(name = "media_teaser_clips",
        indexes = {
                @Index(name = "idx_teaser_item", columnList = "media_item_id, sort_order"),
                @Index(name = "idx_teaser_state", columnList = "state"),
                @Index(name = "idx_teaser_feed", columnList = "published, sort_order, created_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeaserClip {

    public enum State {
        /** Waiting for the single worker. */
        QUEUED,
        /** ffmpeg is running. */
        GENERATING,
        /** The clip is on disk and can be fetched. */
        READY,
        /** ffmpeg failed, timed out, or the server restarted mid-encode. */
        FAILED
    }

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "media_item_id", nullable = false)
    private String mediaItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private State state = State.QUEUED;

    @Column(name = "start_seconds", nullable = false)
    private double startSeconds;

    @Column(name = "end_seconds", nullable = false)
    private double endSeconds;

    /** -1 (fully left) .. 1 (fully right), 0 = centred crop window. */
    @Column(name = "horizontal_offset", nullable = false)
    @Builder.Default
    private double horizontalOffset = 0.0;

    /** Short human label, e.g. "Lobby shootout". */
    @Column(length = 256)
    private String label;

    /** Free-text citation for where the timestamp came from, e.g. a best-scenes list. */
    @Column(name = "source_note", length = 1024)
    private String sourceNote;

    /** The original movie file. Re-validated against the media roots before every use. */
    @Column(name = "source_path", length = 1024)
    private String sourcePath;

    /** The generated clip, inside the teaser output directory. Null while generating. */
    @Column(name = "output_path", length = 1024)
    private String outputPath;

    @Column(name = "output_width")
    private Integer outputWidth;

    @Column(name = "output_height")
    private Integer outputHeight;

    @Column(name = "file_size")
    private Long fileSize;

    /**
     * Gates visibility separately from {@code state == READY}: a clip can finish
     * encoding and sit unpublished while its crop is reviewed before it reaches users.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean published = false;

    /** Lower sorts first in the global feed; 0 is unranked, ordered by createdAt after. */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Lob
    private String error;

    /** The admin account that requested this clip, for audit only. */
    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public boolean isFetchable() {
        return state == State.READY;
    }

    public double durationSeconds() {
        return endSeconds - startSeconds;
    }
}
