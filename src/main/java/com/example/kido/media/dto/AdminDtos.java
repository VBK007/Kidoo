package com.example.kido.media.dto;

import java.util.List;

/**
 * Read models for the admin panel's three tabs.
 *
 * <p>Shaped for what each screen renders rather than for what the tables hold: the
 * Health tab wants two stat cards and a seven-bar chart, so it gets exactly that, with
 * the peak day already identified so the client does not have to scan for it to know
 * which bar to paint solid.
 */
public final class AdminDtos {

    private AdminDtos() {}

    // --- Health tab ---

    /** One live stream, as the panel lists it. */
    public record SessionDto(
            String sessionId,
            String profileId,
            String profileName,
            String deviceName,
            String mediaItemId,
            String itemTitle,
            String mode,
            Integer targetHeight,
            double positionSeconds,
            Double durationSeconds,
            Integer percentComplete,
            double megabitsPerSecond,
            long bytesServed,
            String startedAt,
            long idleSeconds,
            boolean terminated) {}

    /**
     * The CPU/transcode card.
     *
     * @param sessions the transcodes running right now, so the card can name which
     *                 device is transcoding what
     */
    public record TranscodeLoadDto(
            int activeTranscodes,
            int maxTranscodes,
            boolean atCapacity,
            List<SessionDto> sessions) {}

    /**
     * One bar in the seven-day watch chart.
     *
     * @param peak true for the highest day, which the client paints at full strength
     */
    public record WatchDayDto(String date, String label, double hours, boolean peak) {}

    /**
     * An entry in the "Needs a look" list.
     *
     * @param kind   stable key: {@code WRONG_MATCHES}, {@code ALWAYS_TRANSCODES},
     *               {@code DISK_SPACE}, {@code MISSING_FILES}, {@code UNDATED_CLIPS}
     * @param count  how many things are affected, for the mono count on the row
     * @param action client route to open, or null when there is nothing to act on
     */
    public record AttentionItemDto(
            String kind,
            String headline,
            String detail,
            long count,
            String severity,
            String action) {}

    public record HealthTabDto(
            String startedAt,
            long uptimeSeconds,
            int activeStreams,
            long profileCount,
            double totalMegabitsPerSecond,
            TranscodeLoadDto transcodeLoad,
            List<WatchDayDto> watchWeek,
            double watchWeekTotalHours,
            List<AttentionItemDto> needsALook,
            long itemCount,
            long libraryBytes,
            int librariesConfigured,
            boolean ffmpegConfigured) {}

    // --- People tab ---

    /**
     * One person's viewing this month.
     *
     * @param habitLine plain-English summary, e.g. "about 40 minutes a day"
     */
    public record PersonUsageDto(
            String profileId,
            String profileName,
            double hoursThisMonth,
            double sharePercent,
            String habitLine) {}

    /**
     * The suggestion card.
     *
     * <p>Null when there is nothing worth suggesting. The panel should show advice only
     * when the server has evidence for it, not on every visit.
     */
    public record SuggestionDto(String kind, String headline, String detail) {}

    public record PeopleTabDto(
            List<SessionDto> liveSessions,
            List<PersonUsageDto> usageThisMonth,
            double totalHoursThisMonth,
            int peakConcurrentStreams,
            SuggestionDto suggestion) {}

    // --- Disk tab ---

    public record CategoryUsageDto(
            String type,
            String label,
            long itemCount,
            long bytes,
            double percentOfLibrary) {}

    public record VolumeDto(
            String path,
            String store,
            long totalBytes,
            long usableBytes,
            long usedBytes,
            double percentUsed) {}

    /**
     * A large file plus the reason it is worth looking at.
     *
     * @param note the single most useful sentence, or null when the file is unremarkable
     */
    public record BigFileDto(
            String mediaItemId,
            String title,
            String type,
            String fileName,
            long fileSize,
            String quality,
            boolean alwaysTranscodes,
            boolean neverWatched,
            long playCount,
            String note) {}

    /**
     * The "Safe to clear" card.
     *
     * <p>Caches are genuinely safe to delete; {@code watchedByEveryoneBytes} is a
     * suggestion for a human to review, which is why the two are reported separately
     * rather than as one number.
     */
    public record ReclaimableDto(
            long transcodeCacheBytes,
            long trickplayCacheBytes,
            long downloadCacheBytes,
            long watchedByEveryoneBytes,
            int watchedByEveryoneCount,
            long totalBytes) {}

    public record DiskTabDto(
            List<CategoryUsageDto> categories,
            List<VolumeDto> volumes,
            List<BigFileDto> biggestFiles,
            ReclaimableDto reclaimable) {}

    /** What a cache purge actually removed. */
    public record PurgeResultDto(long freedBytes, String detail) {}

    /** Result of backfilling demo rating/view numbers onto movies that had none. */
    public record SeedResultDto(long moviesUpdated, String detail) {}
}
