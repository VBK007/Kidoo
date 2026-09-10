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
 * One recorded sign-in: who, from which device and address, and — where the ingress
 * proxy supplies it — roughly where in the world.
 */
@Entity
@Table(name = "login_events", indexes = @Index(name = "idx_login_events_user_at", columnList = "userId,at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginEvent {

    @Id
    @UuidGenerator
    private String id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String deviceId;

    @Column(nullable = false)
    private String ip;

    /** ISO 3166-1 alpha-2, from the ingress proxy's geo header. Null when it isn't set. */
    private String country;

    /** Only present behind proxies with a geolocation add-on enabled; null otherwise. */
    private String region;

    @Column(length = 512)
    private String userAgent;

    /** Client-declared, e.g. "android", "ios", "web". Free-form — not an enum on purpose. */
    private String platform;

    private String appVersion;

    @Builder.Default
    private Instant at = Instant.now();
}
