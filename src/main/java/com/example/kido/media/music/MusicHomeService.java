package com.example.kido.media.music;

import java.time.Duration;
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
import com.example.kido.media.dto.ArtistDtos.ArtistSummaryDto;
import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;
import com.example.kido.media.dto.HomeDtos.HomeItemDto;
import com.example.kido.media.dto.HomeDtos.HomeRailDto;
import com.example.kido.media.dto.MusicHomeDtos.MusicHomeDto;
import com.example.kido.media.dto.PlaybackDtos.ContinueWatchingDto;
import com.example.kido.media.downloads.ProfileMediaSettings;
import com.example.kido.media.downloads.ProfileMediaSettingsRepository;
import com.example.kido.media.engagement.MediaItemCommentRepository;
import com.example.kido.media.engagement.MediaItemLikeRepository;
import com.example.kido.media.home.TrendingWindow;
import com.example.kido.media.home.WeeklyPopularityRanker;
import com.example.kido.media.playback.PlaybackService;
import com.example.kido.media.session.WatchEventRepository;
import com.example.kido.profile.Profile;

/**
 * Builds the music tab's home screen: mostly browse shelves rather than a ranking —
 * see {@link com.example.kido.media.dto.MusicHomeDtos} for the one ranked exception
 * this now carries.
 */
@Service
public class MusicHomeService {

    private static final List<MediaType> MUSIC_TYPES = List.of(MediaType.MUSIC, MediaType.VIDEO_SONG);

    /** Rails are a swipe, not a page. */
    private static final int MAX_RAIL_SIZE = 50;

    /** "This week" for the trending rail — see {@link WeeklyPopularityRanker}. */
    private static final Duration TRENDING_WINDOW = Duration.ofDays(7);

    /** How many artists lead the "Top artist" row. */
    private static final int MAX_TOP_ARTISTS = 10;

    /** A mood/activity/director shelf with fewer tracks than this is not worth a heading. */
    private static final int MIN_FACET_RAIL_SIZE = 4;

    /** How many singers get a shelf of their own. */
    private static final int MAX_PERSON_RAILS = 5;

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
    private final WatchEventRepository watchEvents;
    private final MediaItemLikeRepository likes;
    private final MediaItemCommentRepository comments;
    private final ArtistService artists;
    private final ProfileMediaSettingsRepository profileSettings;

    public MusicHomeService(MediaItemRepository items,
                            CatalogService catalog,
                            PlaybackService playback,
                            WatchEventRepository watchEvents,
                            MediaItemLikeRepository likes,
                            MediaItemCommentRepository comments,
                            ArtistService artists,
                            ProfileMediaSettingsRepository profileSettings) {
        this.items = items;
        this.catalog = catalog;
        this.playback = playback;
        this.watchEvents = watchEvents;
        this.likes = likes;
        this.comments = comments;
        this.artists = artists;
        this.profileSettings = profileSettings;
    }

    @Transactional(readOnly = true)
    public MusicHomeDto home(Profile profile, int limit) {
        int railSize = Math.min(Math.max(1, limit), MAX_RAIL_SIZE);
        Pageable railPage = PageRequest.of(0, railSize);

        // A household's saved language preference (the same field a profile already
        // sets for which audio/subtitle track a video should auto-play) doubles here as
        // which language's music leads the browse screen — a Telugu track sitting in
        // the same mood or decade as a hundred Tamil ones is correct data, not a shelf
        // anyone asked to see. Search is a different question and is not filtered by
        // this: it stays reachable regardless of what a shelf shows by default.
        String preferredLanguage = profileSettings.findByProfileId(profile.getId())
                .map(ProfileMediaSettings::getPreferredLanguage)
                .filter(lang -> lang != null && !lang.isBlank())
                .orElse(null);
        // Facet queries fetch this many when a language filter will thin the results
        // afterward, rather than the railSize a query with nothing to filter needs —
        // filtering post-query (see MusicHomeDtos for why primaryLanguage is a best-effort
        // guess, not a real lookup) means a fixed railSize fetch can come up short.
        Pageable facetPage = preferredLanguage == null ? railPage : PageRequest.of(0, railSize * 3);

        List<HomeRailDto> rails = new ArrayList<>();

        // One track per album, which the catalog now does for every caller: the
        // interesting question is which *records* turned up, not which files did,
        // and the answer to the second was twenty tiles of one soundtrack.
        List<ItemSummaryDto> recentlyAdded = filterByLanguage(
                catalog.recentlyAdded(profile, MUSIC_TYPES, preferredLanguage == null ? railSize : railSize * 3),
                preferredLanguage);
        addRail(rails, "recently-added", "Recently added", capped(recentlyAdded, railSize));

        // The one ranked exception on this screen — see MusicHomeDtos for why a
        // recent-activity blend is a different, answerable question from the
        // all-time "best track" ranking this home screen otherwise avoids.
        Instant weekAgo = Instant.now().minus(TRENDING_WINDOW);
        List<WeeklyPopularityRanker.Scored> topMusicWeek =
                TrendingWindow.rank(items, watchEvents, likes, comments, MUSIC_TYPES, weekAgo, railSize).stream()
                        .filter(scored -> matchesLanguage(scored.item().getPrimaryLanguage(), preferredLanguage))
                        .toList();
        if (!topMusicWeek.isEmpty()) {
            List<MediaItem> weekItems =
                    topMusicWeek.stream().map(WeeklyPopularityRanker.Scored::item).toList();
            Map<String, ItemSummaryDto> weekSummaries = catalog.summarise(profile, weekItems).stream()
                    .collect(java.util.stream.Collectors.toMap(ItemSummaryDto::id, s -> s));
            List<HomeItemDto> weekTiles = new ArrayList<>();
            for (WeeklyPopularityRanker.Scored scored : topMusicWeek) {
                ItemSummaryDto summary = weekSummaries.get(scored.item().getId());
                if (summary != null) {
                    weekTiles.add(new HomeItemDto(summary, scored.score(), scored.reason()));
                }
            }
            if (!weekTiles.isEmpty()) {
                rails.add(new HomeRailDto("top-music-week", "Top music this week", "trending", weekTiles));
            }
        }

        for (Object[] row : items.countByMood(MUSIC_TYPES)) {
            String mood = (String) row[0];
            long count = ((Number) row[1]).longValue();
            if (count < MIN_FACET_RAIL_SIZE) {
                continue;
            }
            List<MediaItem> found = items.findByMood(MUSIC_TYPES, mood, facetPage);
            List<ItemSummaryDto> summaries = capped(
                    filterByLanguage(catalog.summarise(profile, found), preferredLanguage), railSize);
            if (summaries.size() < MIN_FACET_RAIL_SIZE) {
                continue;
            }
            addRail(rails, "mood:" + mood, MOOD_TITLES.getOrDefault(mood, mood), summaries);
        }

        for (Object[] row : items.countByActivity(MUSIC_TYPES)) {
            String activity = (String) row[0];
            long count = ((Number) row[1]).longValue();
            if (count < MIN_FACET_RAIL_SIZE) {
                continue;
            }
            List<MediaItem> found = items.findByActivity(MUSIC_TYPES, activity, facetPage);
            List<ItemSummaryDto> summaries = capped(
                    filterByLanguage(catalog.summarise(profile, found), preferredLanguage), railSize);
            if (summaries.size() < MIN_FACET_RAIL_SIZE) {
                continue;
            }
            addRail(rails, "activity:" + activity, ACTIVITY_TITLES.getOrDefault(activity, activity), summaries);
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
            List<MediaItem> found = items.findByMusicDirector(MUSIC_TYPES, director, facetPage);
            List<ItemSummaryDto> summaries = capped(
                    filterByLanguage(catalog.summarise(profile, found), preferredLanguage), railSize);
            if (summaries.size() < MIN_FACET_RAIL_SIZE) {
                continue;
            }
            addRail(rails, "director:" + director, director, summaries);
            directorRails++;
        }

        // Singers get their own shelves rather than being folded in with
        // the music directors above, because those are three different reasons to
        // want a song: who wrote it and who sang it. A
        // shelf of Tamil soundtracks gets browsed by both, and collapsing them
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
            List<MediaItem> found = items.findByArtistName(MUSIC_TYPES, singer, facetPage);
            List<ItemSummaryDto> summaries = capped(
                    filterByLanguage(catalog.summarise(profile, found), preferredLanguage), railSize);
            if (summaries.size() < MIN_FACET_RAIL_SIZE) {
                continue;
            }
            addRail(rails, "singer:" + singer, singer, summaries);
            singerRails++;
        }

        // There are no hero shelves. There was an attempt, and it was a bad one: it
        // read castMembers, which is a @Lob and therefore an `oid` on PostgreSQL,
        // where `like` does not exist for that type. Every request to this endpoint
        // returned 500 and the whole music screen fell back to a poster grid. It
        // never produced a single shelf even before that, because the scanner does
        // not put cast on music rows at all — the other candidate column, `people`,
        // is never populated either.
        //
        // Heroes need cast data on music, in something queryable, before they can
        // exist. Until then their absence is the honest state.

        int eraRails = 0;
        for (String decade : decadesNewestFirst(items.countByYear(MUSIC_TYPES))) {
            if (eraRails >= MAX_ERA_RAILS) {
                break;
            }
            int start = Integer.parseInt(decade);
            List<MediaItem> found = items.findByYearBetween(MUSIC_TYPES, start, start + 9, facetPage);
            List<ItemSummaryDto> summaries = capped(
                    filterByLanguage(catalog.summarise(profile, found), preferredLanguage), railSize);
            if (summaries.size() < MIN_FACET_RAIL_SIZE) {
                continue;
            }
            addRail(rails, "era:" + decade, "Best of the " + decade + "s", summaries);
            eraRails++;
        }

        List<ArtistSummaryDto> topArtists = artists.list(0, MAX_TOP_ARTISTS).artists();

        return new MusicHomeDto(
                continueListening(profile, railSize), rails, topArtists, Instant.now().toString());
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
     * Keeps a tile whose language matches the preference, or whose language is unknown
     * — an untagged track (or a {@code VIDEO_SONG} row {@link
     * com.example.kido.media.library.LibraryIngestService#backfillMusicLanguage} does
     * not cover) should not simply vanish from every shelf just for lacking data either
     * way. {@code null} means no preference is saved at all, in which case nothing here
     * is filtered.
     */
    private static List<ItemSummaryDto> filterByLanguage(List<ItemSummaryDto> summaries, String preferredLanguage) {
        if (preferredLanguage == null) {
            return summaries;
        }
        return summaries.stream()
                .filter(summary -> matchesLanguage(summary.language(), preferredLanguage))
                .toList();
    }

    private static boolean matchesLanguage(String itemLanguage, String preferredLanguage) {
        return preferredLanguage == null || itemLanguage == null
                || itemLanguage.equalsIgnoreCase(preferredLanguage);
    }

    private static List<ItemSummaryDto> capped(List<ItemSummaryDto> summaries, int limit) {
        return summaries.size() <= limit ? summaries : summaries.subList(0, limit);
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
