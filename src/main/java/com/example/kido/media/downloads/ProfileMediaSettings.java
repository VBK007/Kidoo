package com.example.kido.media.downloads;

import java.time.Instant;

import org.hibernate.annotations.UuidGenerator;

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
