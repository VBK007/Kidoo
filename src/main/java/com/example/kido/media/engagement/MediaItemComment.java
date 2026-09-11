package com.example.kido.media.engagement;

import java.time.Instant;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One thing somebody in the household said about a title.
 *
 * <p>Written by a profile rather than an account, like a like is: a shared login means
 * "dad thought it dragged" and "the eight-year-old loved it" are the same account and
 * have to be told apart by profile to mean anything.
 *
 * <p>{@code ownerId} is copied from the profile so moderation can be settled without a
 * join — a parent may clear up after their own household and nobody else's, which is a
 * question about the account behind the author, not about the author.
 *
 * <p>The item is referenced by id, not by a JPA association, so a comment survives its
 * file being marked missing while a disk is unplugged. What was said about a film does
 * not stop being true because the drive is out.
 */
@Entity
@Table(name = "media_item_comments",
        indexes = {
                @Index(name = "idx_comment_item", columnList = "media_item_id, created_at"),
                @Index(name = "idx_comment_profile", columnList = "profile_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaItemComment {

    /** As long as a comment may be. Past this the client should be offering a review. */
    public static final int MAX_LENGTH = 1000;

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "media_item_id", nullable = false)
    private String mediaItemId;

    @Column(name = "profile_id", nullable = false)
    private String profileId;

    /** The account the author's profile belongs to. Decides who may moderate it. */
    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    /**
     * The author's name as it stood when they wrote this.
     *
     * <p>A fallback, not the display value: listings overlay the profile's current name
     * so a rename is reflected everywhere at once. It earns its place when the profile
     * is gone — a deleted child should leave "Maya said…" behind rather than a blank.
     */
    @Column(name = "author_name", nullable = false)
    private String authorName;

    @Column(nullable = false, length = MAX_LENGTH)
    private String body;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** Set when the author rewrites it; null while it stands as first posted. */
    @Column(name = "edited_at")
    private Instant editedAt;
}
