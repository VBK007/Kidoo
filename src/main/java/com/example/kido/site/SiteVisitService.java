package com.example.kido.site;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.site.SiteTrafficDtos.CountDto;
import com.example.kido.site.SiteTrafficDtos.DayCountDto;
import com.example.kido.site.SiteTrafficDtos.SiteTrafficDto;

/**
 * Records page views beaconed from the public GitHub Pages site and builds the admin
 * dashboard's traffic view from them.
 */
@Service
public class SiteVisitService {

    /** A beacon this long is not a real path/referrer/user-agent; truncated, not refused —
     * a malformed value is still worth one row over losing the visit entirely. */
    private static final int MAX_FIELD_LENGTH = 512;

    private static final int TOP_N = 8;
    private static final int TRAFFIC_DAYS = 14;

    private final SiteVisitRepository visits;
    private final SiteVisitThrottle throttle;

    public SiteVisitService(SiteVisitRepository visits, SiteVisitThrottle throttle) {
        this.visits = visits;
        this.throttle = throttle;
    }

    /** @return false when the caller has exceeded its rate allowance and nothing was recorded */
    @Transactional
    public boolean record(String callerAddress, String path, String referrer, String userAgent,
                          Integer screenWidth, Integer screenHeight) {
        if (!throttle.allow(callerAddress)) {
            return false;
        }
        String normalisedUserAgent = truncate(blankToNull(userAgent), null);
        visits.save(SiteVisit.builder()
                .path(truncate(blankToNull(path), "/"))
                .referrer(truncate(blankToNull(referrer), null))
                .userAgent(normalisedUserAgent)
                .ipAddress(truncate(blankToNull(callerAddress), null))
                .deviceType(UserAgentParser.deviceType(normalisedUserAgent))
                .os(UserAgentParser.os(normalisedUserAgent))
                .browser(UserAgentParser.browser(normalisedUserAgent))
                .screenWidth(screenWidth)
                .screenHeight(screenHeight)
                .createdAt(Instant.now())
                .build());
        return true;
    }

    @Transactional(readOnly = true)
    public SiteTrafficDto stats() {
        Instant now = Instant.now();
        Instant startOfToday = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant last7 = now.minus(Duration.ofDays(7));
        Instant last14 = now.minus(Duration.ofDays(TRAFFIC_DAYS));
        Instant last30 = now.minus(Duration.ofDays(30));

        return new SiteTrafficDto(
                visits.count(),
                visits.countByCreatedAtGreaterThanEqual(startOfToday),
                visits.countByCreatedAtGreaterThanEqual(last7),
                visits.countByCreatedAtGreaterThanEqual(last30),
                byDay(visits.findCreatedAtSince(last14)),
                topN(visits.topPathsSince(last30, PageRequest.of(0, TOP_N))),
                topN(visits.topReferrersSince(last30, PageRequest.of(0, TOP_N))),
                topN(visits.topDeviceTypesSince(last30, PageRequest.of(0, TOP_N))),
                topN(visits.topBrowsersSince(last30, PageRequest.of(0, TOP_N))),
                topN(visits.topOperatingSystemsSince(last30, PageRequest.of(0, TOP_N))),
                recent(visits.findTop50ByOrderByCreatedAtDesc()));
    }

    private static List<SiteTrafficDtos.VisitDto> recent(List<SiteVisit> rows) {
        List<SiteTrafficDtos.VisitDto> out = new ArrayList<>(rows.size());
        for (SiteVisit v : rows) {
            out.add(new SiteTrafficDtos.VisitDto(
                    v.getCreatedAt().toString(), v.getPath(), v.getReferrer(),
                    v.getIpAddress(), v.getDeviceType(), v.getBrowser(), v.getOs()));
        }
        return out;
    }

    private static List<DayCountDto> byDay(List<Instant> timestamps) {
        ZoneId zone = ZoneId.systemDefault();
        DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;

        // Every day in the window gets a row, zero included, so the admin chart does
        // not have to guess whether a missing day was quiet or just never queried.
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(zone);
        for (int i = TRAFFIC_DAYS - 1; i >= 0; i--) {
            counts.put(today.minusDays(i), 0L);
        }
        for (Instant at : timestamps) {
            LocalDate day = at.atZone(zone).toLocalDate();
            counts.merge(day, 1L, Long::sum);
        }

        List<DayCountDto> out = new ArrayList<>(counts.size());
        counts.forEach((day, count) -> out.add(new DayCountDto(day.format(iso), count)));
        return out;
    }

    private static List<CountDto> topN(List<Object[]> rows) {
        List<CountDto> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            out.add(new CountDto((String) row[0], (Long) row[1]));
        }
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        return value.length() > MAX_FIELD_LENGTH ? value.substring(0, MAX_FIELD_LENGTH) : value;
    }
}
