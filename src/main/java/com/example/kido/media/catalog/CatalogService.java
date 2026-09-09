package com.example.kido.media.catalog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.dto.CatalogDtos.MediaInfoDto;
import com.example.kido.media.dto.CatalogDtos.MovieDetailDto;
import com.example.kido.media.dto.CatalogDtos.MoviePageDto;
import com.example.kido.media.dto.CatalogDtos.MovieSummaryDto;
import com.example.kido.media.dto.CatalogDtos.SubtitleTrackDto;
import com.example.kido.media.metadata.SidecarLocator;
import com.example.kido.media.playback.PlaybackProgress;
import com.example.kido.media.playback.PlaybackService;
import com.example.kido.user.AppUser;

import jakarta.persistence.criteria.JoinType;
import lombok.extern.slf4j.Slf4j;

/**
 * Browsing, searching and detail lookups over the indexed library.
 *
 * <p>Filters are optional and combinable, so they are assembled as JPA
 * {@link Specification}s: a predicate for an absent filter is simply not added. The
 * JPQL alternative — {@code where (:genre is null or g = :genre)} — makes PostgreSQL
 * fail to infer the bind parameter's type when the value is null.
 */
@Slf4j
@Service
public class CatalogService {

    /** Caps the page size a client can ask for, so one request cannot pull the whole library. */
    private static final int MAX_PAGE_SIZE = 100;

    private final MovieRepository movies;
    private final PlaybackService playback;
    private final MediaPaths paths;
    private final SidecarLocator sidecars;

    public CatalogService(MovieRepository movies,
                          PlaybackService playback,
                          MediaPaths paths,
                          SidecarLocator sidecars) {
        this.movies = movies;
        this.playback = playback;
        this.paths = paths;
        this.sidecars = sidecars;
    }

    /**
     * @param query optional case-insensitive substring match on title
     * @param genre optional exact genre match
     * @param sort  one of {@code title}, {@code added}, {@code year}, {@code rating}
     */
    @Transactional(readOnly = true)
    public MoviePageDto browse(AppUser user, String query, String genre, String sort,
                               int page, int size) {

        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        // Composed explicitly rather than chaining: Specification.and rejects a null
        // argument, so an absent filter has to be skipped rather than passed through.
        Specification<Movie> spec = available();
        Specification<Movie> titleFilter = matchesTitle(query);
        if (titleFilter != null) {
            spec = spec.and(titleFilter);
        }
        Specification<Movie> genreFilter = hasGenre(genre);
        if (genreFilter != null) {
            spec = spec.and(genreFilter);
        }

        Page<Movie> results = movies.findAll(
                spec, PageRequest.of(Math.max(0, page), pageSize, sortOf(sort)));

        List<String> ids = results.getContent().stream().map(Movie::getId).toList();
        Map<String, PlaybackProgress> progress = playback.progressByMovieId(user, ids);

        List<MovieSummaryDto> items = results.getContent().stream()
                .map(movie -> toSummary(movie, progress.get(movie.getId())))
                .toList();

        return new MoviePageDto(
                items,
                results.getNumber(),
                results.getSize(),
                results.getTotalElements(),
                results.getTotalPages());
    }

    @Transactional(readOnly = true)
    public MovieDetailDto detail(AppUser user, String movieId) {
        Movie movie = require(movieId);
        PlaybackProgress progress = playback.progressByMovieId(user, List.of(movieId)).get(movieId);

        return new MovieDetailDto(
                movie.getId(),
                movie.getTitle(),
                movie.getOriginalTitle(),
                movie.getYear(),
                movie.getPlot(),
                movie.getTagline(),
                movie.getRuntimeMinutes(),
                movie.getRating(),
                movie.getCertification(),
                movie.getGenres(),
                movie.getDirectors(),
                movie.getCastMembers(),
                movie.getStudio(),
                movie.getQuality(),
                movie.getTmdbId(),
                movie.getImdbId(),
                movie.getFileSize(),
                movie.hasPoster(),
                movie.hasBackdrop(),
                MediaInfoDto.from(movie.getMediaInfo()),
                subtitleTracks(movie),
                progress == null ? null : (int) progress.getPositionSeconds(),
                progress != null && progress.isWatched());
    }

    @Transactional(readOnly = true)
    public List<String> genres() {
        return movies.findDistinctGenres();
    }

    @Transactional(readOnly = true)
    public long count() {
        return movies.countByMissingFalse();
    }

    /** @throws ApiException 404 if unknown, 410 if the file has gone from disk */
    @Transactional(readOnly = true)
    public Movie require(String movieId) {
        Movie movie = movies.findById(movieId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Movie not found"));
        if (movie.isMissing()) {
            // Distinguished from 404 so the client can say "disk offline" rather than
            // "no such movie" — the row is still there, the bytes are not.
            throw new ApiException(HttpStatus.GONE, "Movie file is no longer on disk");
        }
        return movie;
    }

    /**
     * External subtitle files beside the movie, plus whatever the container holds.
     *
     * <p>Indexes are positional and stable only for as long as the folder contents are:
     * the client fetches by the index it was given in this response.
     */
    public List<SubtitleTrackDto> subtitleTracks(Movie movie) {
        List<SubtitleTrackDto> tracks = new ArrayList<>();
        int index = 0;

        for (SidecarLocator.SubtitleTrack track : externalSubtitles(movie)) {
            tracks.add(new SubtitleTrackDto(index++, track.language(), track.format(),
                    track.forced(), track.hearingImpaired(), false));
        }

        // Embedded tracks are reported so the app can show them, but serving them needs
        // an ffmpeg extraction pass that is not wired up yet.
        MediaInfo info = movie.getMediaInfo();
        if (info != null && info.getEmbeddedSubtitles() != null) {
            for (String entry : info.getEmbeddedSubtitles().split(";")) {
                String[] parts = entry.split(":");
                if (parts.length < 3) {
                    continue;
                }
                tracks.add(new SubtitleTrackDto(index++, parts[2], parts[1], false, false, true));
            }
        }
        return tracks;
    }

    /** Resolves the movie file, then lists the subtitle files sitting next to it. */
    public List<SidecarLocator.SubtitleTrack> externalSubtitles(Movie movie) {
        if (!paths.isConfigured()) {
            return List.of();
        }
        try {
            Path file = paths.requireWithinRoots(movie.getFilePath());
            return sidecars.findSubtitles(file);
        } catch (ApiException ex) {
            // A detail view must still render when the disk is unavailable.
            log.debug("Could not list subtitles for {}: {}", movie.getId(), ex.getMessage());
            return List.of();
        }
    }

    private MovieSummaryDto toSummary(Movie movie, PlaybackProgress progress) {
        return MovieSummaryDto.from(
                movie,
                progress == null ? null : (int) progress.getPositionSeconds(),
                progress != null && progress.isWatched());
    }

    // --- specifications ---

    /** Missing files are indexed but never browsable. */
    private static Specification<Movie> available() {
        return (root, query, cb) -> cb.isFalse(root.get("missing"));
    }

    private static Specification<Movie> matchesTitle(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        String pattern = "%" + rawQuery.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern),
                cb.like(cb.lower(root.get("sortTitle")), pattern));
    }

    private static Specification<Movie> hasGenre(String genre) {
        if (genre == null || genre.isBlank()) {
            return null;
        }
        return (root, query, cb) -> {
            // A join onto the element collection multiplies rows, so the count query
            // Spring Data derives for paging needs the same distinct treatment.
            if (query != null) {
                query.distinct(true);
            }
            return cb.equal(cb.lower(root.join("genres", JoinType.INNER)), genre.toLowerCase(Locale.ROOT));
        };
    }

    private static Sort sortOf(String sort) {
        String key = sort == null ? "title" : sort.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "added" -> Sort.by(Sort.Direction.DESC, "addedAt");
            case "year" -> Sort.by(Sort.Order.desc("year").nullsLast(), Sort.Order.asc("sortTitle"));
            case "rating" -> Sort.by(Sort.Order.desc("rating").nullsLast(), Sort.Order.asc("sortTitle"));
            case "title" -> Sort.by(Sort.Direction.ASC, "sortTitle");
            default -> throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Unknown sort '" + sort + "' (expected title, added, year or rating)");
        };
    }
}
