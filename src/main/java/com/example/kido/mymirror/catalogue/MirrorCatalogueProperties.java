package com.example.kido.mymirror.catalogue;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Catalogue-cache configuration. Everything that could change production behaviour is OFF by
 * default, so an unconfigured deployment behaves exactly as it did before this feature existed:
 * no database rows are written or read, and the Home request path is byte-for-byte unchanged.
 *
 * <ul>
 *   <li>{@code persistence.enabled} gates <b>all</b> database access — both the last-known-good
 *       snapshot writes on a successful build and the L2 reads on a warm request. Off by default:
 *       persisting catalogue metadata needs confirmed provider storage permission.</li>
 *   <li>{@code cache-first.enabled} gates whether a saturated Home request falls back to the
 *       cached catalogue instead of an empty one. Off by default, so the controller's fallback is
 *       the pre-existing empty {@code home}.</li>
 *   <li>{@code background-refresh.enabled} is reserved and off by default: no background metadata
 *       crawler runs without confirmed provider permission and a defined request budget.</li>
 * </ul>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.mymirror.catalogue")
public class MirrorCatalogueProperties {

    /** A stable, non-secret deployment token. Never a cookie, token or profile id. */
    private String scope = "shared";

    /** Cap on tiles persisted per scoped OTT, a bound on storage growth. */
    private int maxItems = 2000;

    private final Persistence persistence = new Persistence();
    private final CacheFirst cacheFirst = new CacheFirst();
    private final BackgroundRefresh backgroundRefresh = new BackgroundRefresh();
    private final Freshness freshness = new Freshness();

    @Getter
    @Setter
    public static class Persistence {
        /** Master switch for every database read and write this feature performs. */
        private boolean enabled = false;
    }

    @Getter
    @Setter
    public static class CacheFirst {
        /** Serve the cached catalogue (L1, then L2 when persistence is on) on saturation. */
        private boolean enabled = false;
    }

    @Getter
    @Setter
    public static class BackgroundRefresh {
        /** Reserved; no background crawler runs until this is deliberately enabled. */
        private boolean enabled = false;
    }

    @Getter
    @Setter
    public static class Freshness {
        /** Past this age a snapshot is "stale" and a live refresh is preferred, but it is still served. */
        private Duration softTtl = Duration.ofHours(6);

        /** Past this age a snapshot is expired and no longer served, even as a fallback. */
        private Duration hardExpiry = Duration.ofDays(7);
    }
}
