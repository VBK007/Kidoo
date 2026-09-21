package com.example.kido.poster;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A ready-made poster somebody starts from.
 *
 * <p>A template is a design, not a poster: it carries the arrangement ({@link #layout})
 * and the palettes that arrangement was drawn for ({@link #colorThemes}), and the
 * person's own names, dates and photographs never touch this row.
 *
 * <p>Both of those columns are {@code jsonb} rather than tables of their own. The
 * reasoning is in {@link PosterLayout}: they are read and written whole, and a shape
 * that changes with the editor's features should not be a migration every time a
 * designer wants a new kind of box.
 */
@Entity
@Table(name = "poster_templates",
        indexes = @Index(name = "idx_poster_template_category", columnList = "category"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PosterTemplate {

    @Id
    @UuidGenerator
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PosterCategory category;

    @Column(nullable = false, length = 160)
    private String name;

    /** The preview image the picker draws; a URL this server does not host. */
    @Column(length = 512)
    private String thumbnail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private PosterLayout layout;

    /**
     * Never empty in practice — the service supplies the template's own colours as a
     * single "Default" theme when a client sends none, so the editor always has at
     * least one swatch to draw.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "color_themes", nullable = false)
    @Builder.Default
    private List<ColorTheme> colorThemes = new ArrayList<>();

    /**
     * Unpublished templates stay out of every list except the admin's own.
     *
     * <p>This is what lets a category be worked on in a live database: a half-drawn
     * Diwali set can exist as rows without appearing on anyone's phone.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean published = true;

    /** Ascending within a category; ties fall back to name so a shelf never reshuffles. */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
