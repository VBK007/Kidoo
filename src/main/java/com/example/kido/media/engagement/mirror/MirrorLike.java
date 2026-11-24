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
 * One profile's thumbs-up on one mirror title.
 *
 * <p>The sibling of {@code MediaItemLike} for titles that live on the mirror rather than
 * in this server's own catalogue. The shape is the same — a row per (profile, title) so
 * the client can render a filled or empty heart for the person holding the phone — but the
 * target is a {@code mirrorId}: an opaque external catalogue token (a Netflix/Hotstar id,
 * a Prime ASIN), not a {@code media_items} row. There is therefore no {@code likeCount}
 * column to denormalise onto; the total for a mirror title is a {@code count(*)} over these
 * rows, which is what the unique and lookup indexes here are for.
 *
 * <p>Keyed on profile, like a catalogue like: a household shares one login, so a child's
 * taste should not colour what a parent is shown.
 */
@Entity
@Table(name = "mirror_likes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mirror_like_profile_item", columnNames = {"profile_id", "mirror_id"}),
        indexes = {
                @Index(name = "idx_mirror_like_item", columnList = "mirror_id"),
                @Index(name = "idx_mirror_like_profile", columnList = "profile_id, created_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MirrorLike {

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "profile_id", nullable = false)
    private String profileId;

    /** The external mirror title this likes — never a {@code media_items} id. */
    @Column(name = "mirror_id", nullable = false, length = MirrorIds.MAX_LENGTH)
    private String mirrorId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
