package com.example.kido.media.downloads;

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
 * A copy being prepared for a device to take away.
 *
 * <p>Persisted, unlike a playback session, because preparing one can take an hour and
 * a phone will close the app, lock, and come back later expecting to find its download
 * waiting. A restart mid-conversion is recoverable: the job is marked failed on startup
 * rather than left claiming to be in progress forever.
 */
@Entity
@Table(name = "media_download_jobs",
        indexes = {
                @Index(name = "idx_download_profile", columnList = "profile_id, created_at"),
                @Index(name = "idx_download_state", columnList = "state")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadJob {

    public enum State {
        /** Waiting for the single worker. */
        QUEUED,
        /** ffmpeg is running; {@code percent} is meaningful. */
        CONVERTING,
        /** The file is on disk and can be fetched. */
        READY,
        /** ffmpeg failed, timed out, or the server restarted mid-conversion. */
        FAILED,
        /** Cancelled by the owner of the download. */
        CANCELLED,
        /** Retention elapsed and the prepared file was deleted. */
        EXPIRED
    }

    public enum Mode {
        /**
         * The original file already suits the request, so it is offered as-is. No CPU,
         * no wait, no quality loss — always preferred when the codecs allow it.
         */
        PASSTHROUGH,
        /** Re-encoded to fit the requested ceiling. */
        CONVERT
    }

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "profile_id", nullable = false)
    private String profileId;

    @Column(name = "media_item_id", nullable = false)
    private String mediaItemId;

    /** Denormalised so the Saved list renders without joining the catalog. */
    @Column(name = "item_title", length = 512)
    private String itemTitle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private State state = State.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Mode mode;

    /** Requested vertical resolution; null for a passthrough. */
    @Column(name = "target_height")
    private Integer targetHeight;

    /** Source height, so the client can say "4K to 1080p" rather than just "1080p". */
    @Column(name = "source_height")
    private Integer sourceHeight;

    /** The original file. Re-validated against the media roots before every read. */
    @Column(name = "source_path", length = 1024)
    private String sourcePath;

    /** The prepared file, inside the downloads cache. Null while converting. */
    @Column(name = "output_path", length = 1024)
    private String outputPath;

    @Column(nullable = false)
    @Builder.Default
    private double percent = 0;

    /** Size of the deliverable once known. */
    @Column(name = "file_size")
    private Long fileSize;

    /**
     * Size guess made before conversion starts, so the client can warn about a data
     * plan before committing. Replaced by the real size when the job completes.
     */
    @Column(name = "estimated_bytes")
    private Long estimatedBytes;

    @Lob
    private String error;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public boolean isFetchable() {
        return state == State.READY;
    }

    public boolean isFinished() {
        return state == State.READY || state == State.FAILED
                || state == State.CANCELLED || state == State.EXPIRED;
    }

    /** Bytes to report: the real size when known, else the pre-conversion estimate. */
    public Long reportedBytes() {
        return fileSize != null ? fileSize : estimatedBytes;
    }
}
