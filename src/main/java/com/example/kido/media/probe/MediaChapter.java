package com.example.kido.media.probe;

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
 * One chapter marker inside a file, as the container declares it.
 *
 * <p>A separate table rather than a collection on the item: a film can carry forty
 * chapters, and eager-loading them onto every row of a catalog grid would cost far
 * more than the player screen that actually needs them saves.
 */
@Entity
@Table(name = "media_chapters",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chapter_item_index", columnNames = {"media_item_id", "chapter_index"}),
        indexes = @Index(name = "idx_chapter_item", columnList = "media_item_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaChapter {

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "media_item_id", nullable = false)
    private String mediaItemId;

    @Column(name = "chapter_index", nullable = false)
    private int chapterIndex;

    @Column(name = "start_seconds", nullable = false)
    private double startSeconds;

    @Column(name = "end_seconds")
    private Double endSeconds;

    /** Container-supplied title; often absent, in which case the client numbers them. */
    @Column(length = 512)
    private String title;
}
