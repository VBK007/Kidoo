package com.example.kido.media.engagement.mirror;

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
 * How far a profile got through a mirror title — the Continue Watching row.
 *
 * <p>The resume marker the catalogue keeps as playback progress on a {@code media_items}
 * row, for a title that has no such row. A mirror title lives in an external catalogue, so
 * "pick up where you left off" cannot hang off a media item we own — there is none. It is
 * its own thing, a {@code (profile, mirrorId)} pair keyed on profile so a household sharing
 * one login resumes each to their own place, with the same small title snapshot My List
 * carries so the rail draws its posters without an ad-gated round trip to the mirror per
 * tile.
 *
 * <p>One row per title, not per play: a new position on a title already started overwrites
 * the old one and bumps {@code updatedAt}, which is what orders the rail (most recently
 * watched first). For a series the {@code episodeId} records which episode the position
 * belongs to, so resuming reopens that episode rather than the series placeholder.
 *
 * <p>A finished title keeps its row but drops out of the rail (the list query filters
 * {@code finished = false}); the row lingers only so a re-watch resumes from zero rather
 * than from the credits. Nothing here is a foreign key — the row stands whether or not the
 * mirror still carries the title, exactly as a like, a comment or a saved item on it does.
 */
@Entity
@Table(name = "mirror_progress",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mirror_progress_profile_item", columnNames = {"profile_id", "mirror_id"}),
        indexes = {
                @Index(name = "idx_mirror_progress_profile", columnList = "profile_id, updated_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MirrorProgress {

    /** Matches {@link MirrorSavedItem#NAME_MAX}; the snapshot is the same shape. */
    public static final int NAME_MAX = 512;

    /** Matches {@link MirrorSavedItem#POSTER_MAX}. */
    public static final int POSTER_MAX = 2048;

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "profile_id", nullable = false)
    private String profileId;

    /** The external mirror title — never a {@code media_items} id. */
    @Column(name = "mirror_id", nullable = false, length = MirrorIds.MAX_LENGTH)
    private String mirrorId;

    /** The title's name as it stood when last watched, so the rail draws without a round trip. */
    @Column(nullable = false, length = NAME_MAX)
    private String name;

    /** Poster art URL, snapshotted. Null renders the gradient placeholder, as elsewhere. */
    @Column(name = "poster_url", length = POSTER_MAX)
    private String posterUrl;

    /** Release year, where the tile carried one. */
    @Column(name = "release_year")
    private Integer year;

    /** Which service it came from — "Netflix", "Hotstar", "Prime" — where known. */
    @Column(length = 64)
    private String ott;

    /** Film or series, as the mirror classified it. A label, not an enum we own. */
    @Column(length = 64)
    private String type;

    /**
     * For a series, the episode the position belongs to, so resume reopens that episode
     * and not the series placeholder. Null for a film, where the mirror id itself plays.
     */
    @Column(name = "episode_id", length = MirrorIds.MAX_LENGTH)
    private String episodeId;

    /** Seconds into the title the player had reached when this was last written. */
    @Column(name = "position_seconds", nullable = false)
    private double positionSeconds;

    /** The title's total length, where the player knew it — lets the rail draw a progress bar. */
    @Column(name = "duration_seconds")
    private Double durationSeconds;

    /** Watched to the end. The row stays for a clean re-watch but drops out of the rail. */
    @Column(nullable = false)
    @Builder.Default
    private boolean finished = false;

    /** When the position was last written — the order the Continue Watching rail reads in. */
    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
