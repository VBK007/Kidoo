package com.example.kido.content;

import java.time.Instant;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A dynamically-published piece of content (worlds, daily challenges, chess
 * puzzles, stories, pricing copy, ad config, …). The app fetches these by type
 * and caches them for offline use; publishing bumps the global version.
 */
@Entity
@Table(name = "content_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_content_type_key", columnNames = {"content_type", "content_key"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentItem {

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "content_type", nullable = false)
    private String type;

    @Column(name = "content_key", nullable = false)
    private String key;

    @Column(name = "content_version", nullable = false)
    private long version;

    @Lob
    @Column(name = "body")
    private String body;   // JSON payload the client parses

    @Builder.Default
    private boolean published = true;

    @Builder.Default
    private Instant updatedAt = Instant.now();
}
