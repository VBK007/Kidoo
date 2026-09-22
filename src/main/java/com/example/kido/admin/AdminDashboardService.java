package com.example.kido.admin;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.admin.dto.DashboardDtos.ApplicationStatsDto;
import com.example.kido.admin.dto.DashboardDtos.CatalogStatsDto;
import com.example.kido.admin.dto.DashboardDtos.ContentStatsDto;
import com.example.kido.admin.dto.DashboardDtos.CountDto;
import com.example.kido.admin.dto.DashboardDtos.DashboardDto;
import com.example.kido.admin.dto.DashboardDtos.EngagementDto;
import com.example.kido.admin.dto.DashboardDtos.LibraryCategoryDto;
import com.example.kido.admin.dto.DashboardDtos.LibraryStatsDto;
import com.example.kido.admin.dto.DashboardDtos.PosterStatsDto;
import com.example.kido.admin.dto.DashboardDtos.UserStatsDto;
import com.example.kido.analytics.LoginEventRepository;
import com.example.kido.content.ContentRepository;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.session.PlaybackSession;
import com.example.kido.media.session.PlaybackSessionRegistry;
import com.example.kido.media.session.WatchEventRepository;
import com.example.kido.poster.PosterComponentRepository;
import com.example.kido.poster.PosterTemplateRepository;
import com.example.kido.profile.ProfileRepository;
import com.example.kido.user.Role;
import com.example.kido.user.UserRepository;

/**
 * Counts the whole server for the web admin dashboard.
 *
 * <p>Everything here is an aggregate query. Nothing loads a table to size it: the
 * poster catalog alone seeds to four figures of templates, each carrying a full
 * layout, and a dashboard refreshed every minute cannot afford to read one of those
 * to print the number 1000.
 *
 * <p>"Active" is deliberately defined as <em>signed in</em> rather than <em>watched
 * something</em>. Playback is recorded per profile and says nothing about which
 * account it belonged to, and most of what the apps do — browsing, posters, settings
 * — never starts a stream. Sign-ins are the one per-account trace every client
 * leaves, and they carry the platform, which is what makes the per-application
 * breakdown possible at all.
 */
@Service
public class AdminDashboardService {

    /** Rows for platforms the clients actually reported, plus this one for the rest. */
    private static final String UNKNOWN_PLATFORM = "unknown";

    private static final Duration WEEK = Duration.ofDays(7);
    private static final Duration MONTH = Duration.ofDays(30);

    private final UserRepository users;
    private final ProfileRepository profiles;
    private final LoginEventRepository logins;
    private final MediaItemRepository items;
    private final PosterTemplateRepository templates;
    private final PosterComponentRepository components;
    private final ContentRepository content;
    private final WatchEventRepository watchEvents;
    private final PlaybackSessionRegistry sessions;

    public AdminDashboardService(UserRepository users,
                                 ProfileRepository profiles,
                                 LoginEventRepository logins,
                                 MediaItemRepository items,
                                 PosterTemplateRepository templates,
                                 PosterComponentRepository components,
                                 ContentRepository content,
                                 WatchEventRepository watchEvents,
                                 PlaybackSessionRegistry sessions) {
        this.users = users;
        this.profiles = profiles;
        this.logins = logins;
        this.items = items;
        this.templates = templates;
        this.components = components;
        this.content = content;
        this.watchEvents = watchEvents;
        this.sessions = sessions;
    }

    /** The whole page in one read, so a dashboard's first paint is one request. */
    @Transactional(readOnly = true)
    public DashboardDto dashboard() {
        return new DashboardDto(
                Instant.now().toString(),
                userStats(),
                applications(),
                catalog(),
                engagement());
    }

    // --- users ---

    /**
     * Account totals and recent activity.
     *
     * <p>"Today" is the calendar day in the server's own zone, since that is the day
     * whoever reads this dashboard is having. The 7- and 30-day figures are rolling
     * windows from this instant instead: a "last 7 days" that shrank to a few hours
     * every Monday morning would make the week look like it had collapsed.
     */
    @Transactional(readOnly = true)
    public UserStatsDto userStats() {
        ZoneId zone = ZoneId.systemDefault();
        Instant now = Instant.now();
        Instant startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant weekAgo = now.minus(WEEK);
        Instant monthAgo = now.minus(MONTH);

        return new UserStatsDto(
                users.count(),
                users.countByRole(Role.PARENT),
                users.countByRole(Role.CHILD),
                users.countActivePremium(now),
                users.countByCreatedAtGreaterThanEqual(startOfToday),
                users.countByCreatedAtGreaterThanEqual(weekAgo),
                users.countByCreatedAtGreaterThanEqual(monthAgo),
                profiles.count(),
                logins.countDistinctUsersSince(startOfToday),
                logins.countDistinctUsersSince(weekAgo),
                logins.countDistinctUsersSince(monthAgo));
    }

    // --- per application ---

    /**
     * One row per client application, busiest first.
     *
     * <p>Three aggregates joined in memory rather than one query with three windowed
     * sub-selects: the row count here is the number of apps that have ever signed in,
     * which is a handful, and windowed distinct counts are exactly the kind of SQL
     * that differs between H2 and PostgreSQL.
     */
    @Transactional(readOnly = true)
    public List<ApplicationStatsDto> applications() {
        Instant now = Instant.now();
        Map<String, Long> activeWeek = tally(logins.activeUsersByPlatformSince(now.minus(WEEK)));
        Map<String, Long> activeMonth = tally(logins.activeUsersByPlatformSince(now.minus(MONTH)));
        Map<String, Long> loginsMonth = tally(logins.loginsByPlatformSince(now.minus(MONTH)));

        Map<String, ApplicationStatsDto> apps = new HashMap<>();
        for (Object[] row : logins.platformTotals()) {
            String platform = platform(row[0]);
            long distinctUsers = ((Number) row[1]).longValue();
            Instant lastSeen = (Instant) row[3];

            // A platform can arrive as more than one grouped row — null and "" are two
            // keys in SQL and one app here — so rows are merged rather than collected.
            // Distinct users cannot be summed across them without double-counting
            // anyone present in both, so the larger row wins: it is the closer bound.
            ApplicationStatsDto existing = apps.get(platform);
            apps.put(platform, existing == null
                    ? new ApplicationStatsDto(platform, distinctUsers,
                            activeWeek.getOrDefault(platform, 0L),
                            activeMonth.getOrDefault(platform, 0L),
                            loginsMonth.getOrDefault(platform, 0L),
                            lastSeen == null ? null : lastSeen.toString())
                    : new ApplicationStatsDto(platform,
                            Math.max(existing.users(), distinctUsers),
                            existing.activeLast7Days(),
                            existing.activeLast30Days(),
                            existing.loginsLast30Days(),
                            latest(existing.lastSeenAt(), lastSeen)));
        }

        // Most-used app first: the table should open on the one that matters, and
        // "how many people" is the column it is read for.
        return apps.values().stream()
                .sorted(Comparator.comparingLong(ApplicationStatsDto::users).reversed()
                        .thenComparing(ApplicationStatsDto::platform))
                .toList();
    }

    private static String latest(String current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(Instant.parse(current))
                ? candidate.toString()
                : current;
    }

    /** {@code [platform, count]} rows into a map, with unlabelled platforms named. */
    private static Map<String, Long> tally(List<Object[]> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.merge(platform(row[0]), ((Number) row[1]).longValue(), Long::sum);
        }
        return counts;
    }

    private static String platform(Object raw) {
        String value = raw == null ? null : raw.toString().trim();
        return value == null || value.isEmpty() ? UNKNOWN_PLATFORM : value;
    }

    // --- catalog ---

    @Transactional(readOnly = true)
    public CatalogStatsDto catalog() {
        return new CatalogStatsDto(library(), posters(), contentStats());
    }

    /**
     * Per-category counts from one grouped query.
     *
     * <p>A category with nothing in it is still listed, at zero, so the table has the
     * same rows every time it refreshes instead of growing one when the first anime
     * is added.
     */
    private LibraryStatsDto library() {
        Map<MediaType, Long> counts = new HashMap<>();
        Map<MediaType, Long> bytes = new HashMap<>();
        for (Object[] row : items.countAndBytesByType()) {
            MediaType type = (MediaType) row[0];
            counts.put(type, ((Number) row[1]).longValue());
            bytes.put(type, ((Number) row[2]).longValue());
        }

        List<LibraryCategoryDto> byType = new ArrayList<>();
        for (MediaType type : MediaType.values()) {
            byType.add(new LibraryCategoryDto(
                    type.name(),
                    type.label(),
                    counts.getOrDefault(type, 0L),
                    bytes.getOrDefault(type, 0L)));
        }

        return new LibraryStatsDto(
                counts.getOrDefault(MediaType.FILM, 0L),
                counts.getOrDefault(MediaType.ANIME, 0L),
                counts.getOrDefault(MediaType.SERIES, 0L),
                counts.getOrDefault(MediaType.VIDEO_SONG, 0L),
                counts.getOrDefault(MediaType.HOME_VIDEO, 0L),
                counts.getOrDefault(MediaType.MUSIC, 0L),
                counts.getOrDefault(MediaType.PHOTO, 0L),
                counts.getOrDefault(MediaType.ADULT, 0L),
                items.countByMissingFalseAndHiddenFalse(),
                items.totalBytes(),
                items.countByMissingTrue(),
                items.countByAddedAtGreaterThanEqualAndMissingFalseAndHiddenFalse(
                        Instant.now().minus(WEEK)),
                byType);
    }

    private PosterStatsDto posters() {
        long total = templates.count();
        long published = templates.countByPublishedTrue();
        return new PosterStatsDto(
                total,
                published,
                total - published,
                counts(templates.countByCategory()),
                components.count(),
                counts(components.countByComponentType()));
    }

    private ContentStatsDto contentStats() {
        return new ContentStatsDto(content.count(), content.countByPublishedTrue());
    }

    /** {@code [enum, count]} rows as named tallies, largest first. */
    private static List<CountDto> counts(List<Object[]> rows) {
        return rows.stream()
                .map(row -> new CountDto(String.valueOf(row[0]), ((Number) row[1]).longValue()))
                .sorted(Comparator.comparingLong(CountDto::count).reversed()
                        .thenComparing(CountDto::key))
                .toList();
    }

    // --- engagement ---

    @Transactional(readOnly = true)
    public EngagementDto engagement() {
        List<PlaybackSession> active = sessions.active();
        Set<String> viewers = new LinkedHashSet<>();
        for (PlaybackSession session : active) {
            viewers.add(session.getProfileId());
        }

        ZoneId zone = ZoneId.systemDefault();
        Instant monthStart = LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();

        return new EngagementDto(
                active.size(),
                viewers.size(),
                sessions.peakConcurrent(),
                round(watchEvents.totalSecondsSince(Instant.now().minus(WEEK)) / 3600.0, 1),
                round(watchEvents.totalSecondsSince(monthStart) / 3600.0, 1));
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
