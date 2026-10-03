package com.example.kido.site;

import java.util.List;

/**
 * Read models for the admin dashboard's traffic section — how many people have loaded
 * the public GitHub Pages site, and from where.
 */
public final class SiteTrafficDtos {

    private SiteTrafficDtos() {}

    /** A named count — one path, or one referrer, and how many views it got. */
    public record CountDto(String key, long count) {}

    /** One calendar day's view count, oldest first. */
    public record DayCountDto(String date, long count) {}

    /** One individual page view, newest first — ip/device detail for the admin who
     * wants to see who, not just how many. */
    public record VisitDto(
            String createdAt, String path, String referrer,
            String ipAddress, String deviceType, String browser, String os) {}

    public record SiteTrafficDto(
            long allTime,
            long today,
            long last7Days,
            long last30Days,
            List<DayCountDto> byDayLast14Days,
            List<CountDto> topPaths,
            List<CountDto> topReferrers,
            List<CountDto> topDeviceTypes,
            List<CountDto> topBrowsers,
            List<CountDto> topOperatingSystems,
            List<VisitDto> recentVisits) {}
}
