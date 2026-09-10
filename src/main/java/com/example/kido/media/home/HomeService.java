package com.example.kido.media.home;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;
import com.example.kido.media.dto.HomeDtos.HomeDto;
import com.example.kido.media.dto.HomeDtos.HomeItemDto;
import com.example.kido.media.dto.HomeDtos.HomeRailDto;
import com.example.kido.media.dto.HomeDtos.RankingWeightsDto;
import com.example.kido.media.home.PopularityRanker.Scored;
import com.example.kido.media.playback.PlaybackService;
import com.example.kido.profile.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * Builds the home screen: what to carry on watching, and what to put in front of
 * someone who is not carrying anything on.
 *
 * <p>The headline rail answers the actual question — of everything on this disk, what
 * is worth showing — by blending the three things the server knows about a title: how
 * it was rated, how often it has been played, and how many people in the house liked
 * it. {@link PopularityRanker} holds that arithmetic and why it is shaped the way it
 * is. The single-signal rails below it are kept because they are legible in a way a
 * blend is not: "most watched" is a claim a person can check.
 */
@Slf4j
@Service
public class HomeService {

    /** Rails are a swipe, not a page. */
    private static final int MAX_RAIL_SIZE = 50;

    /**
     * How many candidates each signal contributes to the blended rail.
     *
     * <p>The pool is the union of the top slice of each signal rather than the whole
     * library: scoring happens in memory (normalising against the library maximum is
     * not something SQL expresses portably), so it has to be bounded, and a title
     * outside the top hundred of all three signals cannot reach the top of a blend
     * of them.
     */
    private static final int CANDIDATE_POOL_PER_SIGNAL = 100;

    /** Video only by default — a home screen of poster tiles, not the camera roll. */
    private static final List<MediaType> DEFAULT_TYPES =
            List.of(MediaType.FILM, MediaType.ANIME);

    private final MediaItemRepository items;
    private final CatalogService catalog;
    private final PlaybackService playback;

    public HomeService(MediaItemRepository items,
                       CatalogService catalog,
                       PlaybackService playback) {
        this.items = items;
        this.catalog = catalog;
        this.playback = playback;
    }

    /**
     * @param types the media types to rank over; empty means films and anime
     * @param limit posters per rail
     */
    @Transactional(readOnly = true)
    public HomeDto home(Profile profile, List<MediaType> types, int limit) {
        List<MediaType> requested = types == null || types.isEmpty() ? DEFAULT_TYPES : types;
        int railSize = Math.min(Math.max(1, limit), MAX_RAIL_SIZE);
        Pageable railPage = PageRequest.of(0, railSize);

        List<MediaItem> topRated = items.findTopRated(requested, railPage);
        List<MediaItem> mostPlayed = items.findMostPlayed(requested, railPage);
        List<MediaItem> mostLiked = items.findMostLiked(requested, railPage);
        List<Scored> ranked = rank(requested, railSize);
        List<MediaItem> popular = ranked.stream().map(Scored::item).toList();

        // Every rail is drawn from the same set of entities, so watch progress and this
        // profile's likes are resolved once for the whole screen instead of once per
        // rail — four rails of twenty would otherwise be eight queries just to decide
        // which hearts are filled.
        Map<String, ItemSummaryDto> summaries =
                summarise(profile, popular, topRated, mostPlayed, mostLiked);
        Map<String, Scored> scores = scoresById(ranked);

        List<HomeRailDto> rails = new ArrayList<>();
        addRail(rails, "popular", "Popular in your library", "popularity",
                popular, summaries, scores::get, PopularityRanker::ratingLabel);
        addRail(rails, "top-rated", "Top rated", "rating",
                topRated, summaries, id -> null, PopularityRanker::ratingLabel);
        addRail(rails, "most-watched", "Most watched", "views",
                mostPlayed, summaries, id -> null, PopularityRanker::viewLabel);
        addRail(rails, "most-liked", "Most liked", "likes",
                mostLiked, summaries, id -> null, PopularityRanker::likeLabel);

        // Recently added comes straight from the catalog: it is the one rail that is not
        // a judgement about a title, and it is what keeps a fresh library from looking
        // empty before anything has been rated, played or liked.
        List<ItemSummaryDto> recent = catalog.recentlyAdded(profile, requested, railSize);
        if (!recent.isEmpty()) {
            rails.add(new HomeRailDto("recently-added", "Recently added", "added",
                    recent.stream()
                            .map(summary -> new HomeItemDto(summary, null, "Just added"))
                            .toList()));
        }

        return new HomeDto(
                playback.continueWatching(profile, railSize),
                rails,
                catalog.summary(),
                new RankingWeightsDto(PopularityRanker.RATING_WEIGHT,
                        PopularityRanker.VIEW_WEIGHT,
                        PopularityRanker.LIKE_WEIGHT),
                Instant.now().toString());
    }

    /** The blended rail on its own, without the rest of the screen. */
    @Transactional(readOnly = true)
    public HomeRailDto popularRail(Profile profile, List<MediaType> types, int limit) {
        List<Scored> ranked = rank(types, Math.min(Math.max(1, limit), MAX_RAIL_SIZE));
        List<MediaItem> entities = ranked.stream().map(Scored::item).toList();
        Map<String, ItemSummaryDto> summaries = summarise(profile, entities);

        List<HomeRailDto> rails = new ArrayList<>(1);
        addRail(rails, "popular", "Popular in your library", "popularity",
                entities, summaries, scoresById(ranked)::get, PopularityRanker::ratingLabel);
        return rails.isEmpty()
                ? new HomeRailDto("popular", "Popular in your library", "popularity", List.of())
                : rails.get(0);
    }

    /**
     * The blended ranking as entities and scores, for callers that want the numbers
     * rather than the tiles.
     */
    @Transactional(readOnly = true)
    public List<Scored> rank(List<MediaType> types, int limit) {
        List<MediaType> requested = types == null || types.isEmpty() ? DEFAULT_TYPES : types;
        Pageable pool = PageRequest.of(0, CANDIDATE_POOL_PER_SIGNAL);

        // A map because the three slices overlap heavily — a well-rated title is usually
        // also one that gets played — and an item must be scored once, not three times.
        Map<String, MediaItem> candidates = new LinkedHashMap<>();
        for (MediaItem item : items.findTopRated(requested, pool)) {
            candidates.putIfAbsent(item.getId(), item);
        }
        for (MediaItem item : items.findMostPlayed(requested, pool)) {
            candidates.putIfAbsent(item.getId(), item);
        }
        for (MediaItem item : items.findMostLiked(requested, pool)) {
            candidates.putIfAbsent(item.getId(), item);
        }

        return PopularityRanker.rank(
                candidates.values(), items.averageRating(requested), limit);
    }

    /** Adds a rail unless it would be an empty heading. */
    private void addRail(List<HomeRailDto> rails,
                         String key,
                         String title,
                         String rankedBy,
                         List<MediaItem> entities,
                         Map<String, ItemSummaryDto> summaries,
                         Function<String, Scored> scoreLookup,
                         Function<MediaItem, String> fallbackReason) {
        List<HomeItemDto> tiles = new ArrayList<>(entities.size());
        for (MediaItem item : entities) {
            ItemSummaryDto summary = summaries.get(item.getId());
            if (summary == null) {
                continue;
            }
            Scored scored = scoreLookup.apply(item.getId());
            tiles.add(new HomeItemDto(
                    summary,
                    scored == null ? null : scored.score(),
                    scored == null ? fallbackReason.apply(item) : scored.reason()));
        }
        if (!tiles.isEmpty()) {
            rails.add(new HomeRailDto(key, title, rankedBy, tiles));
        }
    }

    @SafeVarargs
    private Map<String, ItemSummaryDto> summarise(Profile profile, List<MediaItem>... rails) {
        Map<String, MediaItem> distinct = new LinkedHashMap<>();
        for (List<MediaItem> rail : rails) {
            for (MediaItem item : rail) {
                distinct.putIfAbsent(item.getId(), item);
            }
        }
        Map<String, ItemSummaryDto> byId = new LinkedHashMap<>();
        for (ItemSummaryDto summary
                : catalog.summarise(profile, List.copyOf(distinct.values()))) {
            byId.put(summary.id(), summary);
        }
        return byId;
    }

    private static Map<String, Scored> scoresById(List<Scored> ranked) {
        Map<String, Scored> byId = new LinkedHashMap<>();
        for (Scored scored : ranked) {
            byId.put(scored.item().getId(), scored);
        }
        return byId;
    }
}
