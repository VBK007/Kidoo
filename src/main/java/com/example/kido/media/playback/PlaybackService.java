package com.example.kido.media.playback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.catalog.Movie;
import com.example.kido.media.catalog.MovieRepository;
import com.example.kido.media.dto.CatalogDtos.MovieSummaryDto;
import com.example.kido.media.dto.PlaybackDtos.ContinueWatchingDto;
import com.example.kido.media.dto.PlaybackDtos.ProgressDto;
import com.example.kido.media.dto.PlaybackDtos.ProgressRequest;
import com.example.kido.user.AppUser;

import lombok.extern.slf4j.Slf4j;

/**
 * Tracks how far each user has watched, and builds the "continue watching" row.
 */
@Slf4j
@Service
public class PlaybackService {

    /**
     * Past this fraction of the runtime a title counts as finished. Films end with
     * credits nobody watches, so demanding 100% would leave everything permanently
     * "in progress".
     */
    private static final double WATCHED_FRACTION = 0.95;

    /**
     * Below this many seconds nothing is remembered — otherwise every accidental tap
     * on a poster would litter the continue-watching row.
     */
    private static final double MIN_TRACKED_SECONDS = 30;

    private final PlaybackProgressRepository progressRepository;
    private final MovieRepository movies;

    public PlaybackService(PlaybackProgressRepository progressRepository, MovieRepository movies) {
        this.progressRepository = progressRepository;
        this.movies = movies;
    }

    /**
     * Records a client-reported position.
     *
     * <p>Positions arrive every few seconds during playback, so this is an upsert on
     * {@code (user, movie)} rather than an append — the table stays one row per pairing.
     */
    @Transactional
    public ProgressDto record(AppUser user, String movieId, ProgressRequest request) {
        Movie movie = movies.findById(movieId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "Movie not found"));

        double position = Math.max(0, request.positionSeconds());
        Double duration = request.durationSeconds() != null && request.durationSeconds() > 0
                ? request.durationSeconds()
                : durationFromProbe(movie);

        boolean finished = Boolean.TRUE.equals(request.finished())
                || (duration != null && duration > 0 && position >= duration * WATCHED_FRACTION);

        PlaybackProgress progress = progressRepository
                .findByUserIdAndMovieId(user.getId(), movieId)
                .orElseGet(() -> PlaybackProgress.builder()
                        .userId(user.getId())
                        .movieId(movieId)
                        .build());

        progress.setPositionSeconds(position);
        if (duration != null) {
            progress.setDurationSeconds(duration);
        }
        progress.setWatched(finished);
        progress.setUpdatedAt(Instant.now());

        PlaybackProgress saved = progressRepository.save(progress);
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public Optional<ProgressDto> find(AppUser user, String movieId) {
        return progressRepository.findByUserIdAndMovieId(user.getId(), movieId)
                .map(PlaybackService::toDto);
    }

    /** Clears resume state so a title starts from the beginning again. */
    @Transactional
    public void reset(AppUser user, String movieId) {
        progressRepository.deleteByUserIdAndMovieId(user.getId(), movieId);
    }

    /**
     * Resume state for a batch of movies, keyed by movie id.
     *
     * <p>One query for the whole page: doing it per row is the classic N+1 that makes a
     * catalog grid slow once a library gets large.
     */
    @Transactional(readOnly = true)
    public Map<String, PlaybackProgress> progressByMovieId(AppUser user, Collection<String> movieIds) {
        if (movieIds.isEmpty()) {
            return Map.of();
        }
        Map<String, PlaybackProgress> byMovie = new HashMap<>();
        for (PlaybackProgress progress :
                progressRepository.findByUserIdAndMovieIdIn(user.getId(), movieIds)) {
            byMovie.put(progress.getMovieId(), progress);
        }
        return byMovie;
    }

    /**
     * Titles the user has started but not finished, newest first.
     *
     * <p>Rows whose movie has since gone missing from disk are dropped rather than
     * shown as un-playable entries.
     */
    @Transactional(readOnly = true)
    public List<ContinueWatchingDto> continueWatching(AppUser user, int limit) {
        List<PlaybackProgress> started = progressRepository
                .findByUserIdAndWatchedFalseAndPositionSecondsGreaterThanOrderByUpdatedAtDesc(
                        user.getId(), MIN_TRACKED_SECONDS, PageRequest.of(0, Math.max(1, limit)));

        List<ContinueWatchingDto> out = new ArrayList<>();
        for (PlaybackProgress progress : started) {
            Optional<Movie> movie = movies.findById(progress.getMovieId());
            if (movie.isEmpty() || movie.get().isMissing()) {
                continue;
            }
            out.add(new ContinueWatchingDto(
                    MovieSummaryDto.from(movie.get(), (int) progress.getPositionSeconds(), false),
                    progress.getPositionSeconds(),
                    progress.getDurationSeconds(),
                    progress.percentComplete()));
        }
        return out;
    }

    private static Double durationFromProbe(Movie movie) {
        return movie.getMediaInfo() == null ? null : movie.getMediaInfo().getDurationSeconds();
    }

    private static ProgressDto toDto(PlaybackProgress progress) {
        return new ProgressDto(
                progress.getMovieId(),
                progress.getPositionSeconds(),
                progress.getDurationSeconds(),
                progress.isWatched(),
                progress.getUpdatedAt().toString());
    }
}
