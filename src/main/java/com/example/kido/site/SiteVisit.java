package com.example.kido.site;

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
 * One page view of the public GitHub Pages landing site, recorded by a beacon the page
 * fires on load — see {@link SiteVisitController}.
 *
 * <p>Stores the real client IP (see {@code ClientAddress}) and a device
 * classification parsed from the user agent, plus the reported screen size — added
 * 2026-10-01 so the admin traffic view can show who is visiting, not just how many.
 */
@Entity
@Table(name = "site_visits", indexes = @Index(name = "idx_site_visits_created_at", columnList = "created_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SiteVisit {

    @Id
    @UuidGenerator
    private String id;

    @Column(nullable = false)
    private String path;

    private String referrer;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "device_type")
    private String deviceType;

    private String os;

    private String browser;

    @Column(name = "screen_width")
    private Integer screenWidth;

    @Column(name = "screen_height")
    private Integer screenHeight;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
