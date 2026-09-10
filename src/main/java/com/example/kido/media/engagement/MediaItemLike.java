package com.example.kido.media.engagement;

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
 * One profile's thumbs-up on one item.
 *
 * <p>A row per (profile, item) rather than a counter alone, because the client has to
 * render a filled or empty heart for the person holding the phone — a bare total cannot
 * answer "did I like this". The total is kept alongside it on
 * {@code MediaItem.likeCount} so ranking is an indexed sort rather than a join.
 *
 * <p>Keyed on profile, like watch progress: a household shares one login, and a child's
 * taste should not steer what a parent is shown. The item is referenced by id rather
 * than a JPA association so a like survives its file being marked missing while a disk
 * is unplugged.
 */
@Entity
@Table(name = "media_item_likes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_like_profile_item", columnNames = {"profile_id", "media_item_id"}),
        indexes = {
                @Index(name = "idx_like_item", columnList = "media_item_id"),
                @Index(name = "idx_like_profile", columnList = "profile_id, created_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaItemLike {

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "profile_id", nullable = false)
    private String profileId;

    @Column(name = "media_item_id", nullable = false)
    private String mediaItemId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
