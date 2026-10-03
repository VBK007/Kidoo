package com.example.kido.analytics;

import java.time.Instant;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One screen opened in the app: who, which profile was active, and what they landed
 * on. The client logs this on every screen change, so the latest row for an account
 * is — within a navigation or two — what they are currently looking at.
 */
@Entity
@Table(name = "screen_views", indexes = @Index(name = "idx_screen_views_user_at", columnList = "userId,at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScreenView {

    @Id
    @UuidGenerator
    private String id;

    @Column(nullable = false)
    private String userId;

    /** Which child profile was active, if any — some screens (settings, profile picker) have none. */
    private String profileId;

    @Column(nullable = false)
    private String deviceId;

    /** Client-declared screen/route name, e.g. "Home", "MovieDetails", "Player". Free-form, not an enum. */
    @Column(nullable = false)
    private String screen;

    /** The media item the screen is about, if any — what they're actually looking at, not just where. */
    private String itemId;

    private String platform;

    @Builder.Default
    private Instant at = Instant.now();
}
