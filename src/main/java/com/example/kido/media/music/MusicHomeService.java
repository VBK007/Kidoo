package com.example.kido.media.music;

import java.time.Instant;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;
import com.example.kido.media.dto.HomeDtos.HomeItemDto;
import com.example.kido.media.dto.HomeDtos.HomeRailDto;
import com.example.kido.media.dto.MusicHomeDtos.MusicHomeDto;
import com.example.kido.media.dto.PlaybackDtos.ContinueWatchingDto;
import com.example.kido.media.playback.PlaybackService;
import com.example.kido.profile.Profile;

/**
 * Builds the music tab's home screen: browse shelves rather than a ranking.
 *
 * <p>See {@link com.example.kido.media.dto.MusicHomeDtos} for why this does not reuse
 * {@link com.example.kido.media.home.HomeService}'s popularity blend.
 */
@Service
public class MusicHomeService {

    private static final List<MediaType> MUSIC_TYPES = List.of(MediaType.MUSIC, MediaType.VIDEO_SONG);

    /** Rails are a swipe, not a page. */
    private static final int MAX_RAIL_SIZE = 50;

    /** A mood/activity/director shelf with fewer tracks than this is not worth a heading. */
    private static final int MIN_FACET_RAIL_SIZE = 4;

    /**
     * How deep "Recently added" looks before thinning to one track per album.
     *
     * Deep, because tracks arrive in album-sized clumps: copying one soundtrack
     * across writes twenty rows inside the same second, so the twenty newest
     * files are routinely twenty songs off one record. Reading further back is
     * what lets the rail still offer twenty different things.
     *
     * Fifty is also as far as {@code CatalogService.recentlyAdded} will go. A
     * library that has just taken delivery of more than fifty tracks from one
     * album gets a shorter rail, which is the honest answer — there genuinely
     * was only one album added.
     */
    private static final int ALBUM_SCAN_DEPTH = 50;

    /** How many of the library's top music directors get their own rail. */
    private static final int MAX_DIRECTOR_RAILS = 6;

    /** How many decades get their own "Best of the {decade}" rail, most recent first. */
    private static final int MAX_ERA_RAILS = 4;

    private static final Map<String, String> MOOD_TITLES = Map.of(
            "Energetic", "Feeling Energetic",
            "Romantic", "Romantic",
            "Chill", "Chill",
            "Sad", "Sad Songs");

    private static final Map<String, String> ACTIVITY_TITLES = Map.of(
            "Workout", "Workout",
            "Party", "Party",
            "Relax", "Relax",
            "Travel", "Travel");

    private final MediaItemRepository items;
    private final CatalogService catalog;
    private final PlaybackService playback;

    public MusicHomeService(MediaItemRepository items, CatalogService catalog, PlaybackService playback) {
        this.items = items;
        this.catalog = catalog;
        this.playback = playback;
    }

    @Transactional(readOnly = true)
    public MusicHomeDto home(Profile profile, int limit) {
        int railSize = Math.min(Math.max(1, limit), MAX_RAIL_SIZE);
        Pageable railPage = PageRequest.of(0, railSize);

        List<HomeRailDto> rails = new ArrayList<>();

        // One track per album. The interesting question is which *records*
        // turned up, not which files did — and the answer to the second was
        // twenty tiles of the same soundtrack, technically correct and useless.
        List<ItemSummaryDto> recent = oneTrackPerAlbum(
                catalog.recentlyAdded(profile, MUSIC_TYPES, ALBUM_SCAN_DEPTH), railSize);
        addRail(rails, "recently-added", "Recently added", recent);

        for (Object[] row : items.countByMood(MUSIC_TYPES)) {
            String mood = (String) row[0];
            long count = ((Number) row[1]).longValue();
            if (count < MIN_FACET_RAIL_SIZE) {
                continue;
            }
            List<MediaItem> found = items.findByMood(MUSIC_TYPES, mood, railPage);
            addRail(rails, "mood:" + mood, MOOD_TITLES.getOrDefault(mood, mood),
                    catalog.summarise(profile, found));
        }

        for (Object[] row : items.countByActivity(MUSIC_TYPES)) {
            String activity = (String) row[0];
            long count = ((Number) row[1]).longValue();
            if (count < MIN_FACET_RAIL_SIZE) {
                continue;
            }
            List<MediaItem> found = items.findByActivity(MUSIC_TYPES, activity, railPage);
            addRail(rails, "activity:" + activity, ACTIVITY_TITLES.getOrDefault(activity, activity),
                    catalog.summarise(profile, found));
        }

        int directorRails = 0;
        for (Object[] row : items.countByMusicDirector(MUSIC_TYPES)) {
            if (directorRails >= MAX_DIRECTOR_RAILS) {
                break;
            }
            String director = (String) row[0];
            long count = ((Number) row[1]).longValue();
            if (count < MIN_FACET_RAIL_SIZE) {
                continue;
            }
            List<MediaItem> found = items.findByMusicDirector(MUSIC_TYPES, director, railPage);
            addRail(rails, "director:" + director, director, catalog.summarise(profile, found));
            directorRails++;
        }

        int eraRails = 0;
        for (String decade : decadesNewestFirst(items.countByYear(MUSIC_TYPES))) {
            if (eraRails >= MAX_ERA_RAILS) {
                break;
            }
            int start = Integer.parseInt(decade);
            List<MediaItem> found = items.findByYearBetween(MUSIC_TYPES, start, start + 9, railPage);
            if (found.size() < MIN_FACET_RAIL_SIZE) {
                continue;
            }
            addRail(rails, "era:" + decade, "Best of the " + decade + "s",
                    catalog.summarise(profile, found));
            eraRails++;
        }

        return new MusicHomeDto(continueListening(profile, railSize), rails, Instant.now().toString());
    }

    /**
     * {@link PlaybackService#continueWatching} is not type-scoped — it is one row shared
     * with the video home screen — so this filters to audio types after the fact rather
     * than adding a parallel query for what is, underneath, the same playback-progress
     * table.
     */
    private List<ItemSummaryDto> continueListening(Profile profile, int limit) {
        List<ItemSummaryDto> out = new ArrayList<>();
        for (ContinueWatchingDto entry : playback.continueWatching(profile, limit * 4)) {
            String type = entry.item().type();
            if (MediaType.MUSIC.name().equals(type) || MediaType.VIDEO_SONG.name().equals(type)) {
                out.add(entry.item());
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Decades present in the library, newest first — bucketed in Java rather than SQL
     * for the same portability reason {@link MediaItemRepository#countByYear} is already
     * grouped by exact year instead of by decade.
     */
    private static List<String> decadesNewestFirst(List<Object[]> yearCounts) {
        Map<Integer, Long> byDecade = new LinkedHashMap<>();
        for (Object[] row : yearCounts) {
            int year = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            int decade = (year / 10) * 10;
            byDecade.merge(decade, count, Long::sum);
        }
        return byDecade.keySet().stream()
                .sorted((a, b) -> b - a)
                .map(String::valueOf)
                .toList();
    }

    /**
     * Keeps the newest track from each album and drops the rest.
     *
     * <p>Order is preserved, so the album that arrived most recently still comes
     * first and the track standing for it is the newest one off that record.
     *
     * <p>Tracks with no album tag are each kept. They are not evidence of one
     * record arriving twenty times; they are twenty loose files, and collapsing
     * them under a shared "no album" would hide nineteen of them — which is the
     * opposite of what thinning this rail is for. A disk full of untagged MP3s
     * therefore sees no change, correctly.
     *
     * @param tracks newest first
     * @param limit  how many the rail wants
     */
    static List<ItemSummaryDto> oneTrackPerAlbum(List<ItemSummaryDto> tracks, int limit) {
        Set<String> seen = new HashSet<>();
        List<ItemSummaryDto> picked = new ArrayList<>();

        for (ItemSummaryDto track : tracks) {
            if (picked.size() >= limit) {
                break;
            }
            String album = track.album();
            // Case and stray spacing differ between taggers, and "Vikram" twice
            // under two spellings would defeat the whole exercise.
            String key = album == null || album.isBlank()
                    ? "item:" + track.id()
                    : "album:" + album.trim().toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                picked.add(track);
            }
        }
        return picked;
    }

    private static void addRail(List<HomeRailDto> rails, String key, String title, List<ItemSummaryDto> summaries) {
        if (summaries.isEmpty()) {
            return;
        }
        List<HomeItemDto> tiles = summaries.stream().map(item -> new HomeItemDto(item, null, null)).toList();
        rails.add(new HomeRailDto(key, title, "browse", tiles));
    }
}
