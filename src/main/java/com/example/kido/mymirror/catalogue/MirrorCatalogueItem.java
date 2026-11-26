package com.example.kido.mymirror.catalogue;

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
 * One persisted, non-personal catalogue tile: the stable metadata a mirror home tile carries
 * (its id, name, poster reference and display genre), scoped so two deployments — or two OTTs —
 * never share a row.
 *
 * <p>Identity is {@code (catalogue_scope, ott, mirror_id)}, never {@code mirror_id} alone and
 * never anything cookie- or session-derived: a Netflix id and a Hotstar id can collide, two
 * deployments must not cross-fill, and the scope is a stable non-secret deployment token, not a
 * credential. Nothing here is a foreign key — a mirror id names a row in an upstream catalogue,
 * not in this server's own {@code media_items} table, which is the whole reason the mirror path
 * lives apart from the catalogue one.
 *
 * <p>Only permitted non-personal metadata is stored. There is no profile id, no watch state and
 * no saved-item flag here; personal data stays in its own tables. The poster reference is only
 * ever the pure {@code (ott, id)} CDN path the parser derives — never a signed URL, a
 * session-bound query parameter or a credential.
 */
@Entity
@Table(name = "mirror_catalogue_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mirror_catalogue_item",
                columnNames = {"catalogue_scope", "ott", "mirror_id"}),
        indexes = @Index(name = "idx_mirror_catalogue_item_scope", columnList = "catalogue_scope,ott"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MirrorCatalogueItem {

    /** A defensive cap, matching the mirror id bound the engagement path enforces. */
    public static final int MIRROR_ID_MAX = 128;
    public static final int SCOPE_MAX = 64;
    public static final int OTT_MAX = 64;
    public static final int NAME_MAX = 512;
    public static final int POSTER_MAX = 2048;
    public static final int GENRE_MAX = 128;
    public static final int TYPE_MAX = 64;

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "catalogue_scope", nullable = false, length = SCOPE_MAX)
    private String scope;

    @Column(nullable = false, length = OTT_MAX)
    private String ott;

    @Column(name = "mirror_id", nullable = false, length = MIRROR_ID_MAX)
    private String mirrorId;

    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @Column(name = "poster_url", length = POSTER_MAX)
    private String posterUrl;

    /** The display bucket (e.g. {@code Horror}), not a personal tag. */
    @Column(length = GENRE_MAX)
    private String genre;

    @Column(name = "release_year")
    private Integer year;

    @Column(length = TYPE_MAX)
    private String type;

    @Column(name = "first_seen", nullable = false)
    @Builder.Default
    private Instant firstSeen = Instant.now();

    @Column(name = "last_seen", nullable = false)
    @Builder.Default
    private Instant lastSeen = Instant.now();
}
