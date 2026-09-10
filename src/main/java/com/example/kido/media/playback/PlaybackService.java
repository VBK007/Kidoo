package com.example.kido.media.playback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;
import com.example.kido.media.dto.PlaybackDtos.ContinueWatchingDto;
import com.example.kido.media.dto.PlaybackDtos.ProgressDto;
import com.example.kido.media.dto.PlaybackDtos.ProgressRequest;
import com.example.kido.media.dto.PlayerDtos.SubtitleOffsetRequest;
import com.example.kido.media.dto.PlayerDtos.TrackSelectionRequest;
import com.example.kido.media.engagement.LikeService;
import com.example.kido.media.session.PlaybackSessionRegistry;
import com.example.kido.media.session.WatchEvent;
import com.example.kido.media.session.WatchEventRepository;
import com.example.kido.profile.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * Tracks how far each profile has watched, and builds the continue-watching row.
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
     * Below this many seconds nothing is remembered — otherwise every accidental tap on
     * a poster would litter the continue-watching row.
     */
    private static final double MIN_TRACKED_SECONDS = 30;

    /**
     * Headroom over wall-clock time when crediting watch seconds. The player offers
     * long-press 2x playback, so a report can legitimately advance faster than
     * real time.
     */
    private static final double MAX_SPEED_MULTIPLIER = 2.5;

    /** Absolute ceiling per report, for an app suspended for hours then resumed. */
    private static final double MAX_INCREMENT_SECONDS = 600;

    private final PlaybackProgressRepository progressRepository;
    private final MediaItemRepository items;
    private final WatchEventRepository watchEvents;
    private final PlaybackSessionRegistry sessions;
    private final LikeService likes;

    public PlaybackService(PlaybackProgressRepository progressRepository,
                           MediaItemRepository items,
                           WatchEventRepository watchEvents,
                           PlaybackSessionRegistry sessions,
                           LikeService likes) {
        this.progressRepository = progressRepository;
        this.items = items;
        this.watchEvents = watchEvents;
        this.sessions = sessions;
        this.likes = likes;
    }

    /**
     * Records a client-reported position.
     *
     * <p>Positions arrive every few seconds during playback, so this is an upsert on
     * {@code (profile, item)} rather than an append — the table stays one row per pairing.
     */
    @Transactional
    public ProgressDto record(Profile profile, String itemId, ProgressRequest request) {
        MediaItem item = requireItem(itemId);

        double position = Math.max(0, request.positionSeconds());
        Double duration = request.durationSeconds() != null && request.durationSeconds() > 0
                ? request.durationSeconds()
                : durationFromProbe(item);

        boolean finished = Boolean.TRUE.equals(request.finished())
                || (duration != null && duration > 0 && position >= duration * WATCHED_FRACTION);

        PlaybackProgress progress = existingOrNew(profile, itemId);
        Instant now = Instant.now();

        // Append the increment before overwriting the position, since the delta is the
        // only place real watch time can be observed.
        recordWatchIncrement(profile, itemId, progress, position, now);

        progress.setPositionSeconds(position);
        if (duration != null) {
            progress.setDurationSeconds(duration);
        }
        progress.setWatched(finished);
        progress.setUpdatedAt(now);

        // Keeps the admin panel's live view in step with what the player reports.
        sessions.recordProgress(profile.getId(), itemId, position, duration);

        return toDto(progressRepository.save(progress));
    }

    /**
     * Appends the seconds genuinely watched since the previous report.
     *
     * <p>The raw position delta is not usable on its own: a forward seek produces a
     * large jump that was never watched. So the increment is bounded by how much
     * wall-clock time actually passed, with headroom for the 2x speed the player
     * offers, and by an absolute ceiling for the case where an app was suspended for
     * hours and resumed somewhere else in the file.
     */
    private void recordWatchIncrement(Profile profile,
                                      String itemId,
                                      PlaybackProgress previous,
                                      double newPosition,
                                      Instant now) {

        if (previous.getUpdatedAt() == null || previous.getId() == null) {
            // First report for this pairing: there is no previous position to diff.
            return;
        }
        double delta = newPosition - previous.getPositionSeconds();
        if (delta <= 0) {
            // Paused, or rewound. Neither is newly watched time.
            return;
        }
        double wallSeconds = Math.max(0,
                (now.toEpochMilli() - previous.getUpdatedAt().toEpochMilli()) / 1000.0);

        double credited = Math.min(delta, Math.min(wallSeconds * MAX_SPEED_MULTIPLIER + 5,
                MAX_INCREMENT_SECONDS));
        if (credited < 0.5) {
            return;
        }
        watchEvents.save(WatchEvent.builder()
                .profileId(profile.getId())
                .mediaItemId(itemId)
                .secondsWatched(credited)
                .occurredAt(now)
                .build());
    }

    @Transactional(readOnly = true)
    public Optional<ProgressDto> find(Profile profile, String itemId) {
        return progressRepository.findByProfileIdAndMediaItemId(profile.getId(), itemId)
                .map(PlaybackService::toDto);
    }

    @Transactional(readOnly = true)
    public Optional<PlaybackProgress> findEntity(Profile profile, String itemId) {
        return progressRepository.findByProfileIdAndMediaItemId(profile.getId(), itemId);
    }

    /** Clears resume state so a title starts from the beginning again. */
    @Transactional
    public void reset(Profile profile, String itemId) {
        progressRepository.deleteByProfileIdAndMediaItemId(profile.getId(), itemId);
    }

    /**
     * Stores the subtitle sync offset for this profile and file.
     *
     * <p>Kept alongside progress rather than in its own table because it is corrected
     * once per file and then only read when that file is opened.
     */
    @Transactional
    public ProgressDto setSubtitleOffset(Profile profile, String itemId,
                                         SubtitleOffsetRequest request) {
        requireItem(itemId);
        PlaybackProgress progress = existingOrNew(profile, itemId);
        progress.setSubtitleOffsetSeconds(request.offsetSeconds());
        progress.setUpdatedAt(Instant.now());
        return toDto(progressRepository.save(progress));
    }

    /** Remembers the chosen subtitle and audio tracks so playback resumes with them. */
    @Transactional
    public ProgressDto setTracks(Profile profile, String itemId, TrackSelectionRequest request) {
        requireItem(itemId);
        PlaybackProgress progress = existingOrNew(profile, itemId);
        if (request.subtitleTrackIndex() != null) {
            progress.setSubtitleTrackIndex(request.subtitleTrackIndex());
        }
        if (request.audioTrackIndex() != null) {
            progress.setAudioTrackIndex(request.audioTrackIndex());
        }
        progress.setUpdatedAt(Instant.now());
        return toDto(progressRepository.save(progress));
    }

    /**
     * Resume state for a batch of items, keyed by item id.
     *
     * <p>One query for the whole page: doing it per row is the classic N+1 that makes a
     * catalog grid slow once a library grows.
     */
    @Transactional(readOnly = true)
    public Map<String, PlaybackProgress> progressByItemId(Profile profile,
                                                          Collection<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, PlaybackProgress> byItem = new HashMap<>();
        for (PlaybackProgress progress :
                progressRepository.findByProfileIdAndMediaItemIdIn(profile.getId(), itemIds)) {
            byItem.put(progress.getMediaItemId(), progress);
        }
        return byItem;
    }

    /**
     * Titles this profile has started but not finished, newest first.
     *
     * <p>Rows whose file has since gone missing are dropped rather than offered as
     * un-playable entries.
     */
    @Transactional(readOnly = true)
    public List<ContinueWatchingDto> continueWatching(Profile profile, int limit) {
        List<PlaybackProgress> started = progressRepository
                .findByProfileIdAndWatchedFalseAndPositionSecondsGreaterThanOrderByUpdatedAtDesc(
                        profile.getId(), MIN_TRACKED_SECONDS,
                        PageRequest.of(0, Math.max(1, limit)));

        Set<String> liked = likes.likedItemIds(
                profile, started.stream().map(PlaybackProgress::getMediaItemId).toList());

        List<ContinueWatchingDto> out = new ArrayList<>();
        for (PlaybackProgress progress : started) {
            Optional<MediaItem> item = items.findById(progress.getMediaItemId());
            if (item.isEmpty() || !item.get().isBrowsable()) {
                continue;
            }
            out.add(new ContinueWatchingDto(
                    ItemSummaryDto.from(
                            item.get(),
                            (int) progress.getPositionSeconds(),
                            false,
                            progress.percentComplete(),
                            liked.contains(progress.getMediaItemId())),
                    progress.getPositionSeconds(),
                    progress.getDurationSeconds(),
                    progress.percentComplete()));
        }
        return out;
    }

    private PlaybackProgress existingOrNew(Profile profile, String itemId) {
        return progressRepository.findByProfileIdAndMediaItemId(profile.getId(), itemId)
                .orElseGet(() -> PlaybackProgress.builder()
                        .profileId(profile.getId())
                        .mediaItemId(itemId)
                        .build());
    }

    private MediaItem requireItem(String itemId) {
        return items.findById(itemId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "Item not found"));
    }

    private static Double durationFromProbe(MediaItem item) {
        return item.getMediaInfo() == null ? null : item.getMediaInfo().getDurationSeconds();
    }

    private static ProgressDto toDto(PlaybackProgress progress) {
        return new ProgressDto(
                progress.getMediaItemId(),
                progress.getPositionSeconds(),
                progress.getDurationSeconds(),
                progress.isWatched(),
                progress.percentComplete(),
                progress.getSubtitleOffsetSeconds(),
                progress.getSubtitleTrackIndex(),
                progress.getAudioTrackIndex(),
                progress.getUpdatedAt().toString());
    }
}
