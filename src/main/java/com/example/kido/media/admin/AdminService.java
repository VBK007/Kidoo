package com.example.kido.media.admin;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.catalog.MetadataSource;
import com.example.kido.media.dto.AdminDtos.AttentionItemDto;
import com.example.kido.media.dto.AdminDtos.HealthTabDto;
import com.example.kido.media.dto.AdminDtos.PeopleTabDto;
import com.example.kido.media.dto.AdminDtos.PersonUsageDto;
import com.example.kido.media.dto.AdminDtos.SessionDto;
import com.example.kido.media.dto.AdminDtos.SuggestionDto;
import com.example.kido.media.dto.AdminDtos.TranscodeLoadDto;
import com.example.kido.media.dto.AdminDtos.WatchDayDto;
import com.example.kido.media.dto.PlaybackDtos.PlaybackDecisionDto.Mode;
import com.example.kido.media.session.PlaybackSession;
import com.example.kido.media.session.PlaybackSessionRegistry;
import com.example.kido.media.session.WatchEvent;
import com.example.kido.media.session.WatchEventRepository;
import com.example.kido.profile.Profile;
import com.example.kido.profile.ProfileRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Assembles the admin panel's Health and People tabs.
 *
 * <p>Almost everything here is aggregation over data the server already keeps, with one
 * deliberate exception: watch hours are summed from {@link WatchEvent} increments
 * rather than from stored positions, because a position only says where someone got to,
 * never when the time was spent.
 *
 * <p>Watch-hour aggregation happens in Java over a bounded window rather than in SQL.
 * Date bucketing differs between H2 and PostgreSQL, and a household's week of events is
 * a few thousand rows — portability is worth more than the push-down here.
 */
@Slf4j
@Service
public class AdminService {

    private static final int WATCH_CHART_DAYS = 7;

    /**
     * Only suggest a concurrency cap once the household has actually exceeded it.
     * Advice with no evidence behind it is noise.
     */
    private static final int SUGGEST_CAP_ABOVE = 2;

    private final PlaybackSessionRegistry sessions;
    private final WatchEventRepository watchEvents;
    private final MediaItemRepository items;
    private final ProfileRepository profiles;
    private final DiskService disk;
    private final MediaPaths paths;
    private final MediaProperties props;

    /**
     * Process start, for the uptime line. Taken at construction rather than from
     * {@code ProcessHandle}, which reports the JVM's start including Gradle's own
     * bootstrap when run from the build tool.
     */
    private final Instant startedAt = Instant.now();

    public AdminService(PlaybackSessionRegistry sessions,
                        WatchEventRepository watchEvents,
                        MediaItemRepository items,
                        ProfileRepository profiles,
                        DiskService disk,
                        MediaPaths paths,
                        MediaProperties props) {
        this.sessions = sessions;
        this.watchEvents = watchEvents;
        this.items = items;
        this.profiles = profiles;
        this.disk = disk;
        this.paths = paths;
        this.props = props;
    }

    // --- Health ---

    @Transactional(readOnly = true)
    public HealthTabDto healthTab() {
        List<PlaybackSession> active = sessions.active();
        List<WatchDayDto> week = watchWeek();

        return new HealthTabDto(
                startedAt.toString(),
                Duration.between(startedAt, Instant.now()).toSeconds(),
                active.size(),
                profiles.count(),
                round(sessions.totalBitrateBps() / 1_000_000.0, 1),
                transcodeLoad(active),
                week,
                round(week.stream().mapToDouble(WatchDayDto::hours).sum(), 1),
                needsALook(),
                items.countByMissingFalseAndHiddenFalse(),
                items.totalBytes(),
                paths.libraryRoots().size(),
                isFfmpegConfigured());
    }

    private TranscodeLoadDto transcodeLoad(List<PlaybackSession> active) {
        List<SessionDto> transcoding = active.stream()
                .filter(session -> session.getMode() == Mode.TRANSCODE)
                .map(AdminService::toDto)
                .toList();
        return new TranscodeLoadDto(
                transcoding.size(),
                props.getMaxTranscodeSessions(),
                transcoding.size() >= props.getMaxTranscodeSessions(),
                transcoding);
    }

    /**
     * The seven-day bar chart, oldest to newest, with the peak day marked.
     *
     * <p>Every day in the window is present even at zero hours, so the client can draw
     * seven bars without inventing gaps.
     */
    @Transactional(readOnly = true)
    public List<WatchDayDto> watchWeek() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate from = today.minusDays(WATCH_CHART_DAYS - 1L);

        // One indexed aggregate per bar, rather than loading the week's events and
        // bucketing them here. A busy household writes a progress increment every few
        // seconds, so the in-memory version had to carry thousands of rows to produce
        // seven numbers.
        double[] hoursPerDay = new double[WATCH_CHART_DAYS];
        double peakHours = 0;
        for (int offset = 0; offset < WATCH_CHART_DAYS; offset++) {
            LocalDate day = from.plusDays(offset);
            Instant dayStart = day.atStartOfDay(zone).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
            hoursPerDay[offset] = watchEvents.sumSecondsBetween(dayStart, dayEnd) / 3600.0;
            peakHours = Math.max(peakHours, hoursPerDay[offset]);
        }

        List<WatchDayDto> chart = new ArrayList<>();
        for (int offset = 0; offset < WATCH_CHART_DAYS; offset++) {
            LocalDate day = from.plusDays(offset);
            chart.add(new WatchDayDto(
                    day.toString(),
                    day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                            .toUpperCase(Locale.ENGLISH),
                    round(hoursPerDay[offset], 2),
                    // Only mark a peak that is real; seven zero-hour bars have no peak.
                    peakHours > 0 && Math.abs(hoursPerDay[offset] - peakHours) < 1e-9));
        }
        return chart;
    }

    /**
     * The "Needs a look" list — only rows that actually need looking at.
     *
     * <p>Every entry is suppressed when its count is zero, so an idle, healthy server
     * shows an empty list rather than a wall of reassuring noise.
     */
    @Transactional(readOnly = true)
    public List<AttentionItemDto> needsALook() {
        List<AttentionItemDto> attention = new ArrayList<>();

        long guessed = items.countByMetadataSourceAndMissingFalseAndHiddenFalse(
                MetadataSource.FILENAME);
        if (guessed > 0) {
            attention.add(new AttentionItemDto(
                    "WRONG_MATCHES",
                    guessed + (guessed == 1 ? " title was" : " titles were") + " guessed from the filename",
                    "No metadata sidecar was found, so the title and year may be wrong.",
                    guessed,
                    "INFO",
                    "/admin/fix-matches"));
        }

        long alwaysTranscodes = items
                .countByTranscodeCountGreaterThanAndDirectPlayCountAndMissingFalse(0, 0);
        if (alwaysTranscodes > 0) {
            attention.add(new AttentionItemDto(
                    "ALWAYS_TRANSCODES",
                    alwaysTranscodes + (alwaysTranscodes == 1 ? " file always transcodes"
                            : " files always transcode"),
                    "These have never played directly on any device, so each view costs CPU. "
                            + "Re-encoding them once would remove that cost.",
                    alwaysTranscodes,
                    "WARN",
                    "/admin/disk"));
        }

        long missing = items.countByMissingTrue();
        if (missing > 0) {
            attention.add(new AttentionItemDto(
                    "MISSING_FILES",
                    missing + (missing == 1 ? " file is" : " files are") + " no longer on disk",
                    "Their watch history is kept in case the drive is only unmounted.",
                    missing,
                    "WARN",
                    "/admin/disk"));
        }

        long undated = items.countByTypeInAndCapturedAtIsNullAndMissingFalseAndHiddenFalse(
                List.of(MediaType.HOME_VIDEO, MediaType.PHOTO));
        if (undated > 0) {
            attention.add(new AttentionItemDto(
                    "UNDATED_CLIPS",
                    undated + (undated == 1 ? " clip has" : " clips have") + " no date",
                    "They cannot be placed on the timeline until they are tagged.",
                    undated,
                    "INFO",
                    "/timeline"));
        }

        // Disk pressure last, but escalated: it is the one that eventually breaks things.
        disk.volumes().stream()
                .filter(volume -> volume.percentUsed() >= 90)
                .findFirst()
                .ifPresent(volume -> attention.add(new AttentionItemDto(
                        "DISK_SPACE",
                        "Disk is " + volume.percentUsed() + "% full",
                        "Only " + humanBytes(volume.usableBytes()) + " free on " + volume.path(),
                        Math.round(volume.percentUsed()),
                        "CRITICAL",
                        "/admin/disk")));

        return attention;
    }

    // --- People ---

    @Transactional(readOnly = true)
    public PeopleTabDto peopleTab() {
        List<SessionDto> live = sessions.active().stream().map(AdminService::toDto).toList();

        ZoneId zone = ZoneId.systemDefault();
        Instant monthStart = LocalDate.now(zone).withDayOfMonth(1)
                .atStartOfDay(zone).toInstant();

        Map<String, Double> secondsByProfile = new LinkedHashMap<>();
        for (Object[] row : watchEvents.secondsByProfileSince(monthStart)) {
            secondsByProfile.put((String) row[0], ((Number) row[1]).doubleValue());
        }

        double totalSeconds = secondsByProfile.values().stream()
                .mapToDouble(Double::doubleValue).sum();
        long daysElapsed = Math.max(1,
                ChronoUnit.DAYS.between(monthStart, Instant.now()) + 1);

        List<PersonUsageDto> usage = new ArrayList<>();
        for (Profile profile : profiles.findAll()) {
            double seconds = secondsByProfile.getOrDefault(profile.getId(), 0.0);
            usage.add(new PersonUsageDto(
                    profile.getId(),
                    profile.getName(),
                    round(seconds / 3600.0, 1),
                    totalSeconds == 0 ? 0 : round(seconds * 100.0 / totalSeconds, 1),
                    habitLine(seconds, daysElapsed)));
        }
        usage.sort(Comparator.comparingDouble(PersonUsageDto::hoursThisMonth).reversed());

        return new PeopleTabDto(
                live,
                usage,
                round(totalSeconds / 3600.0, 1),
                sessions.peakConcurrent(),
                suggestion().orElse(null));
    }

    /** Plain-English daily average, since raw monthly hours are hard to feel. */
    private static String habitLine(double seconds, long daysElapsed) {
        if (seconds <= 0) {
            return "nothing watched this month";
        }
        double minutesPerDay = seconds / 60.0 / daysElapsed;
        if (minutesPerDay < 5) {
            return "barely any, most days";
        }
        if (minutesPerDay < 60) {
            return "about " + Math.round(minutesPerDay / 5) * 5 + " minutes a day";
        }
        double hoursPerDay = minutesPerDay / 60.0;
        return "about " + round(hoursPerDay, 1) + " hours a day";
    }

    /**
     * A single actionable suggestion, or nothing.
     *
     * <p>Ordered by how much the owner would care: sustained transcoding is a real cost
     * today, whereas a concurrency cap is a precaution.
     */
    @Transactional(readOnly = true)
    public Optional<SuggestionDto> suggestion() {
        long alwaysTranscodes = items
                .countByTranscodeCountGreaterThanAndDirectPlayCountAndMissingFalse(0, 0);
        if (alwaysTranscodes >= 3) {
            return Optional.of(new SuggestionDto(
                    "REENCODE_ALWAYS_TRANSCODES",
                    "Re-encode the " + alwaysTranscodes + " files that always transcode?",
                    "Each of them costs CPU on every single view. Converting them once to "
                            + "H.264/AAC would let every device play them directly."));
        }

        int peak = sessions.peakConcurrent();
        if (peak > SUGGEST_CAP_ABOVE) {
            return Optional.of(new SuggestionDto(
                    "CAP_CONCURRENT_STREAMS",
                    "Cap concurrent streams at " + SUGGEST_CAP_ABOVE + "?",
                    "You have had " + peak + " at once. Each transcode uses several cores, "
                            + "so beyond that everyone's playback starts to stutter."));
        }
        return Optional.empty();
    }

    /** Ends a live stream on the owner's instruction. */
    public boolean endSession(String sessionId) {
        return sessions.terminate(sessionId);
    }

    public List<SessionDto> liveSessions() {
        return sessions.active().stream().map(AdminService::toDto).toList();
    }

    private static SessionDto toDto(PlaybackSession session) {
        return new SessionDto(
                session.getId(),
                session.getProfileId(),
                session.getProfileName(),
                session.getDeviceName(),
                session.getMediaItemId(),
                session.getItemTitle(),
                session.getMode().name(),
                session.getTargetHeight(),
                round(session.getPositionSeconds(), 1),
                session.getDurationSeconds(),
                session.percentComplete(),
                round(session.getBitrateBps() / 1_000_000.0, 2),
                session.getBytesServed().get(),
                session.getStartedAt().toString(),
                session.idleMillis() / 1000,
                session.isTerminated());
    }

    /** ffmpeg is only "configured" if the path is set; whether it runs is another matter. */
    private boolean isFfmpegConfigured() {
        String path = props.getFfmpegPath();
        return path != null && !path.isBlank();
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return round(value, 1) + " " + units[unit];
    }
}
