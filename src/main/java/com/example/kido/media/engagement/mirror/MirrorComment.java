package com.example.kido.media.engagement.mirror;

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
 * One thing somebody in the household said about a mirror title.
 *
 * <p>The sibling of {@code MediaItemComment} for titles that live on the mirror rather
 * than in this server's own catalogue. Everything about moderation is identical — written
 * by a profile, not an account, so "dad thought it dragged" and "the eight-year-old loved
 * it" can be told apart under one shared login; {@code ownerId} copied from the profile so
 * a parent can clear up after their own household without a join; {@code authorName}
 * snapshotted so a deleted child still leaves "Maya said…" behind rather than a blank.
 *
 * <p>The one difference is the target: a {@code mirrorId} string — an opaque external
 * catalogue token — rather than a {@code media_items} id. Nothing here is a foreign key, so
 * a comment stands whether or not the mirror still carries the title, exactly as a
 * catalogue comment outlives its file being marked missing.
 */
@Entity
@Table(name = "mirror_comments",
        indexes = {
                @Index(name = "idx_mirror_comment_item", columnList = "mirror_id, created_at"),
                @Index(name = "idx_mirror_comment_profile", columnList = "profile_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MirrorComment {

    /** As long as a comment may be — the same ceiling a catalogue comment has. */
    public static final int MAX_LENGTH = 1000;

    @Id
    @UuidGenerator
    private String id;

    /** The external mirror title this is about — never a {@code media_items} id. */
    @Column(name = "mirror_id", nullable = false, length = MirrorIds.MAX_LENGTH)
    private String mirrorId;

    @Column(name = "profile_id", nullable = false)
    private String profileId;

    /** The account the author's profile belongs to. Decides who may moderate it. */
    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    /**
     * The author's name as it stood when they wrote this.
     *
     * <p>A fallback, not the display value: listings overlay the profile's current name
     * so a rename is reflected everywhere at once. It earns its place when the profile is
     * gone — a deleted child should leave "Maya said…" behind rather than a blank.
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
