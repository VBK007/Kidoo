package com.example.kido.mymirror.catalogue;

import java.time.Instant;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The last-known-good composed catalogue for one {@code (catalogue_scope, ott)} — the whole set of
 * home rails, in order, serialized to one bounded text column so a warm Home read is a single-row
 * lookup with no N+1 fan-out.
 *
 * <p>{@code rails_json} is the serialized, <b>sanitized</b> {@code List<MirrorRailDto>}: rail
 * identifiers, rail titles, rail ordering, within-rail tile ordering (so Top 10 order and every
 * rail's order survive a restart) and each tile's id/title/poster. Playlists are stripped before
 * persistence — a tile's playlist JSON can carry signed, session-bound stream URLs, which are
 * never stored; the client re-fetches a playlist on tap. The poster reference is the parser's pure
 * {@code (ott, id)} CDN path or null, never a signed URL.
 *
 * <p>Write safety: {@code build_seq} is a process-monotonic token assigned when a build starts, so
 * a slow build that started earlier cannot overwrite a newer one that finished first; {@code
 * version} is JPA optimistic locking, so two publishers racing on the one row cannot interleave a
 * lost update; and a {@code complete} snapshot is never overwritten by a {@code partial} one. The
 * three timestamps are kept distinct on purpose: {@code last_successful_refresh} is when the
 * catalogue was last rebuilt from upstream, {@code last_attempted_refresh} is when a refresh was
 * last tried (success or not), and {@code last_access} is when it was last served — soft TTL and
 * hard expiry read the first, not the last.
 */
@Entity
@Table(name = "mirror_catalogue_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mirror_catalogue_snapshot",
                columnNames = {"catalogue_scope", "ott"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MirrorCatalogueSnapshot {

    public static final int SCOPE_MAX = 64;
    public static final int OTT_MAX = 64;

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "catalogue_scope", nullable = false, length = SCOPE_MAX)
    private String scope;

    @Column(nullable = false, length = OTT_MAX)
    private String ott;

    /**
     * The serialized, sanitized rails. Plain-String text column — Postgres {@code text}, H2
     * {@code varchar} — never {@code @Lob}/oid, which breaks inline reads and streaming.
     */
    @Column(name = "rails_json", nullable = false, columnDefinition = "text")
    private String railsJson;

    @Column(nullable = false)
    @Builder.Default
    private boolean complete = false;

    /** Process-monotonic build token; a lower value never overwrites a higher one. */
    @Column(name = "build_seq", nullable = false)
    @Builder.Default
    private long buildSeq = 0L;

    @Column(name = "rail_count", nullable = false)
    @Builder.Default
    private int railCount = 0;

    @Column(name = "item_count", nullable = false)
    @Builder.Default
    private int itemCount = 0;

    @Column(name = "last_successful_refresh", nullable = false)
    @Builder.Default
    private Instant lastSuccessfulRefresh = Instant.now();

    @Column(name = "last_attempted_refresh", nullable = false)
    @Builder.Default
    private Instant lastAttemptedRefresh = Instant.now();

    @Column(name = "last_access", nullable = false)
    @Builder.Default
    private Instant lastAccess = Instant.now();

    @Version
    @Column(nullable = false)
    @Builder.Default
    private long version = 0L;
}
