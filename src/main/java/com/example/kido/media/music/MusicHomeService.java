package com.example.kido.media.music;

import java.time.Instant;
import java.util.HashMap;
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

    /** How many singers, and how many heroes, get a shelf of their own. */
    private static final int MAX_PERSON_RAILS = 5;

    /** How many candidate rows a person query pulls before the name check thins them. */
    private static final int PERSON_SCAN_DEPTH = 120;

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
        Pageable personPage = PageRequest.of(0, PERSON_SCAN_DEPTH);

        List<HomeRailDto> rails = new ArrayList<>();

        // One track per album. The interesting question is which *records*
        // turned up, not which files did — and the answer to the second was
        // twenty tiles of the same soundtrack, technically correct and useless.
        List<ItemSummaryDto> recent = CatalogService.oneItemPerRelease(
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

        // Singers and heroes get their own shelves rather than being folded in with
        // the music directors above, because those are three different reasons to
        // want a song: who wrote it, who sang it, and whose film it came from. A
        // shelf of Tamil soundtracks gets browsed by all three, and collapsing them
        // into one "artist" rail would be the app choosing which may be asked.
        //
        // Singers come off the normalised artistNames join, so a collaboration counts
        // toward everyone it names and the shelf is an indexed lookup rather than a
        // LIKE over a credit line.
        int singerRails = 0;
        for (Object[] row : items.countByArtistName(MUSIC_TYPES)) {
            if (singerRails >= MAX_PERSON_RAILS) {
                break;
            }
            String singer = (String) row[0];
            if (((Number) row[1]).longValue() < MIN_FACET_RAIL_SIZE) {
                continue;
            }
            List<MediaItem> found = items.findByArtistName(MUSIC_TYPES, singer, railPage);
            addRail(rails, "singer:" + singer, singer, catalog.summarise(profile, found));
            singerRails++;
        }

        // Heroes cannot: castMembers is a single CLOB with no join table behind it, so
        // the names are split out here and the rows narrowed by a LIKE that a second
        // pass then checks properly.
        addHeroRails(rails, profile, personPage, railSize);

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
     * A shelf for each of the most-filmed heroes.
     *
     * <p>Singers get the normalised {@code artistNames} join and a straightforward
     * grouped count. Cast has no such table — it is one CLOB per row — so the leads
     * are counted by splitting those credit lines here, and the tracks found again by
     * a LIKE narrowed to rows mentioning the name, then checked properly against the
     * split names. Without that second pass a shelf for "Raja" would collect every
     * Yuvan Shankar Raja track in the house.
     *
     * <p>Only the first name in the billing order counts. Taking the whole cast would
     * hand a shelf to every character actor who has been near a film with a
     * soundtrack, and "hero" is a claim about the lead — the person somebody means
     * when they say they want a Vijay song.
     */
    private void addHeroRails(List<HomeRailDto> rails, Profile profile,
                              Pageable personPage, int railSize) {
        Map<String, Integer> leads = new HashMap<>();
        for (Object[] row : items.musicCredits(MUSIC_TYPES)) {
            ArtistNames.split((String) row[0]).stream()
                    .findFirst()
                    .ifPresent(lead -> leads.merge(lead, 1, Integer::sum));
        }

        List<String> busiestFirst = leads.entrySet().stream()
                .filter(entry -> entry.getValue() >= MIN_FACET_RAIL_SIZE)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();

        int added = 0;
        for (String name : busiestFirst) {
            if (added >= MAX_PERSON_RAILS) {
                break;
            }
            List<MediaItem> found = items.findByCastMentioning(MUSIC_TYPES, name, personPage)
                    .stream()
                    .filter(item -> ArtistNames.split(item.getCastMembers()).stream()
                            .anyMatch(credited -> credited.equalsIgnoreCase(name)))
                    .limit(railSize)
                    .toList();
            if (found.size() < MIN_FACET_RAIL_SIZE) {
                continue;
            }
            addRail(rails, "hero:" + name, name, catalog.summarise(profile, found));
            added++;
        }
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

    private static void addRail(List<HomeRailDto> rails, String key, String title, List<ItemSummaryDto> summaries) {
        if (summaries.isEmpty()) {
            return;
        }
        List<HomeItemDto> tiles = summaries.stream().map(item -> new HomeItemDto(item, null, null)).toList();
        rails.add(new HomeRailDto(key, title, "browse", tiles));
    }
}
