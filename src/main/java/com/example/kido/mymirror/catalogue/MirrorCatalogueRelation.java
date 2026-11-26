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
 * One persisted, non-personal recommendation edge: a "More Like &lt;title&gt;" rail is the
 * mirror's own suggest graph made visible, so each such rail becomes a set of
 * {@code source_ref -> related_id} edges here.
 *
 * <p>Scoped exactly like {@link MirrorCatalogueItem} — {@code (catalogue_scope, ott, ...)} — so a
 * recommendation never bleeds across OTTs or deployments. {@code source_ref} is the rail's own
 * stable slug key (e.g. {@code more-like-stranger-things}), not a secret and not fabricated: it is
 * the edge the rail already represents. {@code ordinal} preserves the order the mirror listed the
 * related titles in. Nothing here is a foreign key, and nothing here is personal — these are the
 * catalogue's own "because this, then that" edges, kept apart from any user's saved items or
 * watch history.
 */
@Entity
@Table(name = "mirror_catalogue_relation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mirror_catalogue_relation",
                columnNames = {"catalogue_scope", "ott", "source_ref", "related_id", "kind"}),
        indexes = @Index(name = "idx_mirror_catalogue_relation_src",
                columnList = "catalogue_scope,ott,source_ref"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MirrorCatalogueRelation {

    public static final int SCOPE_MAX = 64;
    public static final int OTT_MAX = 64;
    public static final int SOURCE_REF_MAX = 256;
    public static final int RELATED_ID_MAX = 128;
    public static final int KIND_MAX = 64;

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "catalogue_scope", nullable = false, length = SCOPE_MAX)
    private String scope;

    @Column(nullable = false, length = OTT_MAX)
    private String ott;

    @Column(name = "source_ref", nullable = false, length = SOURCE_REF_MAX)
    private String sourceRef;

    @Column(name = "related_id", nullable = false, length = RELATED_ID_MAX)
    private String relatedId;

    @Column(nullable = false, length = KIND_MAX)
    private String kind;

    @Column(nullable = false)
    private int ordinal;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
