package com.example.kido.media.trickplay;

import java.time.Instant;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Describes the sprite sheets generated for one item, and the state of that generation.
 *
 * <p>The client needs this to do thumbnail scrubbing without a round trip per frame:
 * given a timecode it computes which sheet holds the frame and where in the grid it
 * sits, then crops locally. That is also what makes the neighbouring-frames filmstrip
 * cheap — six frames usually come from one already-cached sheet.
 *
 * <pre>
 * frameIndex  = floor(seconds / intervalSeconds)
 * sheetIndex  = frameIndex / (columns * rows)
 * indexInSheet= frameIndex % (columns * rows)
 * column      = indexInSheet % columns
 * row         = indexInSheet / columns
 * </pre>
 */
@Entity
@Table(name = "media_trickplay",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_trickplay_item", columnNames = "media_item_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrickplayManifest {

    public enum State {
        /** Queued or actively running ffmpeg. */
        GENERATING,
        /** Sheets are on disk and complete. */
        READY,
        /** ffmpeg failed or timed out; {@code error} says why. */
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
    private State state = State.GENERATING;

    /** Seconds between captured frames. */
    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds;

    /** Pixel size of a single tile. */
    @Column(name = "tile_width")
    private int tileWidth;

    @Column(name = "tile_height")
    private int tileHeight;

    @Column(nullable = false)
    private int columns;

    @Column(name = "row_count", nullable = false)
    private int rows;

    /** Total frames captured across every sheet. */
    @Column(name = "frame_count")
    private int frameCount;

    /** How many sheet files exist. */
    @Column(name = "sheet_count")
    private int sheetCount;

    /** Directory holding the sheets; inside the trickplay cache, never a media root. */
    @Column(name = "cache_dir", length = 1024)
    private String cacheDir;

    @Column(length = 512)
    private String error;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    public int framesPerSheet() {
        return Math.max(1, columns) * Math.max(1, rows);
    }

    public boolean isReady() {
        return state == State.READY && sheetCount > 0;
    }
}
