package com.example.kido.media.cast;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A cast/crew member's photo, fetched once from TMDB and cached locally.
 *
 * <p>Keyed by {@link CastPhotoService#slugify a slug of the person's name} rather than
 * a surrogate id: nothing else in this schema gives a cast member an identity beyond a
 * name string ({@code MediaItem.castMembers} is flat, comma-separated text), so the
 * normalised name is the only stable key two different movies crediting the same actor
 * can share.
 */
@Entity
@Table(name = "cast_photos")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CastPhoto {

    public enum State {
        /** A photo was found and is cached on disk. */
        READY,
        /** TMDB has no matching person, or the person has no profile photo. */
        NOT_FOUND,
        /** The lookup itself failed (network, timeout) — not cached long; retried. */
        FAILED
    }

    /** A slug of the person's name, e.g. {@code "tom-cruise"}. */
    @Id
    private String id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private State state;

    @Column(name = "tmdb_person_id")
    private Integer tmdbPersonId;

    /** The cached file, inside the cast-photos cache. Null unless {@link State#READY}. */
    @Column(name = "photo_path", length = 1024)
    private String photoPath;

    @Column(length = 512)
    private String error;

    @Column(name = "fetched_at", nullable = false)
    @Builder.Default
    private Instant fetchedAt = Instant.now();
}
