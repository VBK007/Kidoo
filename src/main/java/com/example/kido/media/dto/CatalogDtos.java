package com.example.kido.media.dto;

import java.util.List;
import java.util.Set;

import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.Movie;

/**
 * Read models for browsing. Deliberately split in two: a list of 800 movies must not
 * carry every plot summary and cast list, so {@link MovieSummaryDto} holds what a grid
 * cell renders and {@link MovieDetailDto} is fetched only when a title is opened.
 *
 * <p>No absolute filesystem path is ever exposed — artwork and video are addressed by
 * movie id through the API instead.
 */
public final class CatalogDtos {

    private CatalogDtos() {}

    public record MovieSummaryDto(
            String id,
            String title,
            Integer year,
            Integer runtimeMinutes,
            Double rating,
            String quality,
            Set<String> genres,
            boolean hasPoster,
            boolean hasBackdrop,
            Integer resumePositionSeconds,
            boolean watched) {

        public static MovieSummaryDto from(Movie movie, Integer resumeSeconds, boolean watched) {
            return new MovieSummaryDto(
                    movie.getId(),
                    movie.getTitle(),
                    movie.getYear(),
                    movie.getRuntimeMinutes(),
                    movie.getRating(),
                    movie.getQuality(),
                    movie.getGenres(),
                    movie.hasPoster(),
                    movie.hasBackdrop(),
                    resumeSeconds,
                    watched);
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

    public record MovieDetailDto(
            String id,
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
            long fileSize,
            boolean hasPoster,
            boolean hasBackdrop,
            MediaInfoDto mediaInfo,
            List<SubtitleTrackDto> subtitles,
            Integer resumePositionSeconds,
            boolean watched) {}

    public record MoviePageDto(
            List<MovieSummaryDto> items,
            int page,
            int size,
            long totalItems,
            int totalPages) {}

    public record GenreDto(String name) {}
}
