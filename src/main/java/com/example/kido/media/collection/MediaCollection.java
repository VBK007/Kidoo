package com.example.kido.media.collection;

import java.time.Instant;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A saved {@link com.example.kido.media.query.CatalogQuery} with a name on it.
 *
 * <p>That is the whole idea: "4K I have never watched" is not a new kind of thing the
 * catalog has to learn, it is a filter somebody kept. So this table stores the query as
 * JSON and nothing else clever — the query object validates itself on the way in and on
 * the way out, so a row cannot describe a search the browse endpoint could not.
 *
 * <p>Two rows that look alike mean different things:
 *
 * <p><b>A custom collection</b> carries {@link #queryJson} and the owner's own name for
 * it. <b>A built-in</b> carries only {@link #builtinKey} and exists solely to remember
 * that somebody pinned or renamed it — the definition lives in {@link BuiltinCollection}
 * as code. Storing built-in definitions as seeded rows would mean a later version could
 * not improve one without a data migration, and a fresh install would need seeding
 * before its home screen worked.
 */
@Entity
@Table(name = "media_collections",
        indexes = {
                @Index(name = "idx_collection_owner", columnList = "owner_id"),
                @Index(name = "idx_collection_profile", columnList = "profile_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaCollection {

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    /**
     * Whose collection it is, or null for the whole household.
     *
     * <p>Null is the default because most of these are about the library rather than
     * about a person — "4K HDR" means the same thing to everyone in the house. A
     * profile is set only where the query asks about that profile, which the watch and
     * like filters do.
     */
    @Column(name = "profile_id")
    private String profileId;

    /** The owner's name for it; null on a built-in that has not been renamed. */
    @Column(length = 128)
    private String name;

    /** An emoji or short token the client draws; null falls back to the built-in's own. */
    @Column(length = 32)
    private String icon;

    /**
     * The saved query, as JSON. Null on a built-in, whose definition is code.
     *
     * @see CollectionQueryCodec
     */
    @Lob
    @Column(name = "query_json")
    private String queryJson;

    /**
     * Names a {@link BuiltinCollection} when this row is only remembering that one was
     * pinned or renamed. Null on a collection the owner wrote.
     */
    @Column(name = "builtin_key", length = 64)
    private String builtinKey;

    /** Pinned collections get a rail on the home screen. */
    @Column(nullable = false)
    @Builder.Default
    private boolean pinned = false;

    /** Ascending; ties fall back to name so a list never reshuffles between requests. */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /** True when this row only carries pin and rename state for a built-in. */
    public boolean isBuiltin() {
        return builtinKey != null;
    }
}
