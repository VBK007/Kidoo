package com.example.kido.media.downloads;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-profile media preferences, chiefly what to do when away from home Wi-Fi.
 *
 * <p>Kept in the media module rather than added to {@code Profile}, which belongs to the
 * kids-app side of this codebase. A media setting has no business widening that entity,
 * and keeping it separate means the media module could be lifted out without dragging
 * unrelated columns with it.
 */
@Entity
@Table(name = "media_profile_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_media_settings_profile", columnNames = "profile_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileMediaSettings {

    /** What to do when the client is not on the home network. */
    public enum AwayBehaviour {
        /** Show the stream-or-saved sheet every time. The default. */
        ASK,
        /** Never stream over mobile data; offer only what is already downloaded. */
        SAVED_ONLY,
        /** Stream anyway, transcoded down to suit the connection. */
        STREAM
    }

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "profile_id", nullable = false)
    private String profileId;

    /**
     * Backs "When I leave home Wi-Fi → Play saved copies only", and the sheet's
     * "Remember this choice".
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "away_behaviour", nullable = false, length = 32)
    @Builder.Default
    private AwayBehaviour awayBehaviour = AwayBehaviour.ASK;

    /** Default vertical resolution for offline copies this profile asks for. */
    @Column(name = "download_height")
    private Integer downloadHeight;

    /** Ceiling applied when streaming away from home, where upload is the constraint. */
    @Column(name = "away_max_height")
    private Integer awayMaxHeight;

    /**
     * Preferred audio/subtitle language as an ISO 639-1 code, or null for none chosen.
     *
     * <p>One code rather than an ordered list: the client asks a single question on
     * first run, and a ranked fallback chain nobody was asked about would be an
     * invention rather than a preference. Length allows for a region suffix
     * ({@code pt-BR}) should one ever be needed.
     */
    @Column(name = "preferred_language", length = 16)
    private String preferredLanguage;

    /**
     * Genres this profile said it likes, for ordering what gets shown first.
     *
     * <p>A hint, never a filter: a library is small enough that hiding two thirds of it
     * because of three taps on a first-run screen would be worse than useless. Eager
     * because there are at most a handful and every read of these settings wants them.
     *
     * <p>Free strings matched against {@code media_item_genres} rather than a foreign
     * key, since a genre the library does not currently carry is still a legitimate
     * answer — the next film added may well have it.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "media_settings_genres",
            joinColumns = @JoinColumn(name = "settings_id"),
            indexes = @Index(name = "idx_media_settings_genre", columnList = "genre"))
    @Column(name = "genre", length = 128)
    @Builder.Default
    private Set<String> preferredGenres = new LinkedHashSet<>();

    /**
     * Whether mono technical badges are shown for this profile.
     *
     * <p>Stored server-side so the choice follows a person between their devices,
     * which is the point of it being per profile rather than per install.
     */
    @Column(name = "show_technical_badges", nullable = false)
    @Builder.Default
    private boolean showTechnicalBadges = true;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
