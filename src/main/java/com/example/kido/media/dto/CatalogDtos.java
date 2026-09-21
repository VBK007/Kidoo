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

    /**
     * What a poster tile needs, and nothing more.
     *
     * <p>Carries the three ranking signals — {@code rating}, {@code viewCount} and
     * {@code likeCount} — plus this profile's own {@code liked} flag, so a tile can
     * render its heart and its "played 12 times" label from the listing it came in,
     * without a follow-up call per poster.
     */
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
            String album,
            String musicDirector,
            String mood,
            String activity,
            /**
             * What language this was tagged with, or null when nothing recorded one.
             *
             * <p>On the summary and not only on the detail because a queue is built
             * out of summaries, and a queue is exactly where a mixed library is felt:
             * a Tamil song followed by an English one is a jarring thing to happen by
             * itself, three tracks into an evening.
             */
            String language,
            long viewCount,
            long likeCount,
            boolean liked) {

        public static ItemSummaryDto from(MediaItem item,
                                          Integer resumeSeconds,
                                          boolean watched,
                                          Integer percentComplete,
                                          boolean liked) {
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
                    item.getAlbum(),
                    item.getMusicDirector(),
                    item.getMood(),
                    item.getActivity(),
                    item.getPrimaryLanguage(),
                    item.playCount(),
                    item.getLikeCount(),
                    liked);
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
            /** Spoken languages as ISO 639-1 codes, empty when the file is unprobed. */
            Set<String> languages,
            String primaryLanguage,
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
            boolean watched,
            long viewCount,
            long likeCount,
            boolean liked,
            long commentCount) {}

    public record ItemPageDto(
            List<ItemSummaryDto> items,
            int page,
            int size,
            long totalItems,
            int totalPages) {}

    /**
     * The same grid-tile shape as {@link ItemSummaryDto}, minus every per-profile
     * field — this is what an unauthenticated visitor is allowed to see, so there is
     * no resume position, watched mark, or like to leak from someone else's account.
     */
    public record PublicItemSummaryDto(
            String id,
            String type,
            String title,
            Integer year,
            Integer runtimeMinutes,
            Double rating,
            Set<String> genres,
            boolean hasPoster,
            boolean hasBackdrop,
            String artist,
            String album,
            String language) {

        public static PublicItemSummaryDto from(MediaItem item) {
            return new PublicItemSummaryDto(
                    item.getId(),
                    item.getType().name(),
                    item.getTitle(),
                    item.getYear(),
                    item.getRuntimeMinutes(),
                    item.getRating(),
                    item.getGenres(),
                    item.hasPoster(),
                    item.hasBackdrop(),
                    item.getArtist(),
                    item.getAlbum(),
                    item.getPrimaryLanguage());
        }
    }

    public record PublicItemPageDto(
            List<PublicItemSummaryDto> items,
            int page,
            int size,
            long totalItems,
            int totalPages) {}

    /**
     * The same title-opened view as {@link ItemDetailDto}, minus resume/watched state,
     * engagement counts and {@code liked} — all per-profile — and minus {@code
     * fileSize}/{@code fileName}, which a signed-out visitor has no business seeing.
     */
    public record PublicItemDetailDto(
            String id,
            String type,
            String title,
            String originalTitle,
            Integer year,
            String plot,
            String tagline,
            Integer runtimeMinutes,
            Double rating,
            String certification,
            Set<String> genres,
            String language,
            String directors,
            String castMembers,
            String studio,
            String quality,
            String imdbId,
            String artist,
            String album,
            Integer trackNumber,
            boolean hasPoster,
            boolean hasBackdrop,
            MediaInfoDto mediaInfo,
            List<SubtitleTrackDto> subtitles,
            List<PlayerDtos.AudioTrackDto> audioTracks) {}

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
     * One language the library holds.
     *
     * @param code ISO 639-1 where the language has one, e.g. {@code ta}
     * @param name its English name, resolved server-side so each client does not need
     *             its own copy of the ISO table to draw the same chip
     */
    public record LanguageDto(String code, String name) {}

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
