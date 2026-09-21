package com.example.kido.poster;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

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
 * A font, sticker or frame that templates point at instead of copying.
 *
 * <p>Shared on purpose: the same gold mandala sits on a dozen marriage templates, and
 * as a row it is fetched and cached once by the client rather than once per template.
 * A template refers to one of these by id, and {@code PosterTemplateService} refuses a
 * template whose references do not resolve — a poster that renders with a hole where a
 * sticker should be is worse than a rejected save.
 */
@Entity
@Table(name = "poster_components",
        uniqueConstraints = @UniqueConstraint(name = "uk_poster_component_type_name",
                columnNames = {"component_type", "name"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PosterComponent {

    @Id
    @UuidGenerator
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "component_type", nullable = false, length = 32)
    private PosterComponentType type;

    @Column(nullable = false, length = 160)
    private String name;

    /** The file, for the kinds that are one. Null on a frame described by properties. */
    @Column(length = 512)
    private String url;

    /**
     * Everything else the renderer needs, as JSON: {@code borderColor},
     * {@code borderWidth}, {@code cornerRadius}, a font's {@code weight}, and whatever
     * a later kind of component turns out to need.
     *
     * <p>Untyped because this is the extension point. A record here would mean a
     * migration and a release every time a designer wants a second border style, and
     * nothing on the server reads these values — they are carried to the client that
     * draws them.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    @Builder.Default
    private Map<String, Object> properties = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
