package com.example.kido.media.dto;

import java.util.List;
import java.util.Set;

import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaType;

/**
 * Read models for browsing.
 *
 * <p>Deliberately split in two: a grid of several hundred items must not carry every
 * plot summary and cast list, so {@link ItemSummaryDto} holds what a grid cell renders
 * and {@link ItemDetailDto} is fetched only when a title is opened.
 *
 * <p>No absolute filesystem path is ever exposed — artwork, video and subtitles are
 * addressed by item id through the API instead.
 */
public final class CatalogDtos {

    private CatalogDtos() {}

    /** What a poster tile needs, and nothing more. */
    public record ItemSummaryDto(
            String id,
            String type,
            String title,
            Integer year,
            Integer runtimeMinutes,
            Double rating,
            String quality,
            Set<String> genres,
            boolean hasPoster,
            boolean hasBackdrop,
            boolean missing,
            Integer resumePositionSeconds,
            boolean watched,
            Integer percentComplete,
            String capturedAt,
            String artist,
            String album) {

        public static ItemSummaryDto from(MediaItem item,
                                          Integer resumeSeconds,
                                          boolean watched,
                                          Integer percentComplete) {
            return new ItemSummaryDto(
                    item.getId(),
                    item.getType().name(),
                    item.getTitle(),
                    item.getYear(),
                    item.getRuntimeMinutes(),
                    item.getRating(),
                    item.getQuality(),
                    item.getGenres(),
                    item.hasPoster(),
                    item.hasBackdrop(),
                    item.isMissing(),
                    resumeSeconds,
                    watched,
                    percentComplete,
                    item.getCapturedAt() == null ? null : item.getCapturedAt().toString(),
                    item.getArtist(),
                    item.getAlbum());
        }
    }

    public record MediaInfoDto(
            String container,
            Double durationSeconds,
            String videoCodec,
            Integer width,
            Integer height,
            Long bitrate,
            String audioCodecs,
            Integer audioChannels,
            boolean probed) {

        public static MediaInfoDto from(MediaInfo info) {
            if (info == null) {
                return new MediaInfoDto(null, null, null, null, null, null, null, null, false);
            }
            return new MediaInfoDto(
                    info.getContainer(),
                    info.getDurationSeconds(),
                    info.getVideoCodec(),
                    info.getWidth(),
                    info.getHeight(),
                    info.getBitrate(),
                    info.getAudioCodecs(),
                    info.getAudioChannels(),
                    info.isProbed());
        }
    }

    public record SubtitleTrackDto(
            int index,
            String language,
            String format,
            boolean forced,
            boolean hearingImpaired,
            boolean embedded) {}

    public record ItemDetailDto(
            String id,
            String type,
            String libraryName,
            String title,
            String originalTitle,
            Integer year,
            String plot,
            String tagline,
            Integer runtimeMinutes,
            Double rating,
            String certification,
            Set<String> genres,
            String directors,
            String castMembers,
            String studio,
            String quality,
            String tmdbId,
            String imdbId,
            String artist,
            String album,
            Integer trackNumber,
            String capturedAt,
            String place,
            Set<String> people,
            long fileSize,
            String fileName,
            boolean hasPoster,
            boolean hasBackdrop,
            MediaInfoDto mediaInfo,
            List<SubtitleTrackDto> subtitles,
            List<PlayerDtos.AudioTrackDto> audioTracks,
            Integer resumePositionSeconds,
            boolean watched) {}

    public record ItemPageDto(
            List<ItemSummaryDto> items,
            int page,
            int size,
            long totalItems,
            int totalPages) {}

    /** One category chip, with the counts the client shows beneath the library title. */
    public record CategoryDto(String type, String label, long itemCount, long totalBytes) {

        public static CategoryDto from(MediaType type, long itemCount, long totalBytes) {
            return new CategoryDto(type.name(), type.label(), itemCount, totalBytes);
        }
    }

    /** Library header: total count and size, plus per-category breakdown. */
    public record LibrarySummaryDto(
            long itemCount,
            long totalBytes,
            List<CategoryDto> categories,
            List<String> genres) {}

    /**
     * A group on the home-video timeline — one month, one person or one place,
     * depending on the requested grouping.
     *
     * @param key   stable grouping key, e.g. {@code 2024-12}
     * @param label what the left gutter shows, e.g. {@code DEC / 2024}
     */
    public record TimelineGroupDto(
            String key,
            String label,
            long itemCount,
            List<ItemSummaryDto> items) {}

    /**
     * A page of the timeline.
     *
     * <p>Groups can span pages, since items are paged and then grouped — a month is an
     * unbounded group, so the item is the only unit that can be bounded. The client
     * merges groups by {@code key} as it scrolls.
     */
    public record TimelineDto(
            String groupBy,
            List<TimelineGroupDto> groups,
            long undatedCount,
            int page,
            int size,
            long totalItems,
            int totalPages,
            boolean hasMore) {}
}
