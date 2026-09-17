package com.example.kido.media.recommend;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;
import com.example.kido.media.dto.RecommendationDtos.RecommendationDto;
import com.example.kido.media.dto.RecommendationDtos.RecommendationsDto;
import com.example.kido.media.dto.RecommendationDtos.TasteFacetDto;
import com.example.kido.media.playback.PlaybackProgress;
import com.example.kido.media.playback.PlaybackProgressRepository;
import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.query.CatalogQuery.WatchedBy;
import com.example.kido.media.query.CatalogQuerySpecs;
import com.example.kido.media.recommend.RecommendationScorer.Recommended;
import com.example.kido.media.recommend.TasteProfile.Facet;
import com.example.kido.profile.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * "What should I watch?", answered for one profile.
 *
 * <p>Three steps, each of which already existed as a piece: pick candidates with a
 * {@link CatalogQuery}, work out what this person likes with {@link TasteProfiler}, and
 * order the result with {@link RecommendationScorer}. Nothing here queries the database
 * by hand, which is the payoff from making the filter an object.
 *
 * <p>Two exclusions worth stating. Anything this profile has finished is filtered in SQL,
 * because "watch it again" is a different feature. Anything half-watched is dropped after,
 * because it belongs on the continue-watching row and putting it in both places makes the
 * screen look like it has less in it than it does.
 */
@Slf4j
@Service
public class RecommendationService {

    /** A rail is a swipe, not a page. Matches the home screen's own cap. */
    private static final int MAX_RESULTS = 50;

    /**
     * How many unwatched titles are scored.
     *
     * <p>Scoring is in memory — a taste vector is not something SQL expresses — so the
     * pool has to be bounded. Ordered by rating, because a title outside the library's
     * best few hundred is not going to reach the top of a blend that is 30% quality.
     */
    private static final int CANDIDATE_POOL = 300;

    /** Poster tiles, not the camera roll — the same default the rest of the home screen uses. */
    private static final List<MediaType> DEFAULT_TYPES =
            List.of(MediaType.FILM, MediaType.ANIME);

    /** Below this a title is barely started, so it is a recommendation and not a resume. */
    private static final double IN_FLIGHT_SECONDS = 60;

    private final MediaItemRepository items;
    private final PlaybackProgressRepository progress;
    private final CatalogService catalog;
    private final TasteProfiler profiler;

    public RecommendationService(MediaItemRepository items,
                                 PlaybackProgressRepository progress,
                                 CatalogService catalog,
                                 TasteProfiler profiler) {
        this.items = items;
        this.progress = progress;
        this.catalog = catalog;
        this.profiler = profiler;
    }

    @Transactional(readOnly = true)
    public RecommendationsDto forProfile(Profile profile, List<MediaType> types, int limit) {
        List<MediaType> requested = types == null || types.isEmpty() ? DEFAULT_TYPES : types;
        int wanted = Math.min(Math.max(1, limit), MAX_RESULTS);

        TasteProfile taste = profiler.forProfile(profile);
        List<MediaItem> candidates = candidates(profile, requested);
        List<Recommended> ranked = RecommendationScorer.rank(
                candidates, taste, items.averageRating(requested), Instant.now(), wanted);

        Map<String, ItemSummaryDto> summaries = summarise(profile, ranked);
        List<RecommendationDto> picks = new ArrayList<>(ranked.size());
        for (Recommended recommended : ranked) {
            ItemSummaryDto summary = summaries.get(recommended.item().getId());
            if (summary != null) {
                picks.add(new RecommendationDto(summary, recommended.score(),
                        recommended.reason(), recommended.tasteScore(),
                        recommended.qualityScore(), recommended.freshnessScore()));
            }
        }

        return new RecommendationsDto(picks, topFacets(taste), taste.signalCount(),
                taste.isEmpty());
    }

    /**
     * Unwatched titles, best-rated first, minus anything already in flight.
     *
     * <p>The watch filter runs in SQL — that is what {@link WatchedBy#NOT_ME} is for — so
     * the pool is three hundred genuinely unwatched titles rather than three hundred rows
     * of which some are dropped afterwards.
     */
    private List<MediaItem> candidates(Profile profile, List<MediaType> types) {
        CatalogQuery query = CatalogQuery.builder()
                .types(Set.copyOf(types))
                .watched(WatchedBy.NOT_ME)
                .sort("rating")
                .build()
                .validated();

        List<MediaItem> pool = items.findAll(
                        CatalogQuerySpecs.toSpecification(
                                query, profile == null ? null : profile.getId()),
                        PageRequest.of(0, CANDIDATE_POOL))
                .getContent();

        Set<String> inFlight = inFlight(profile);
        return pool.stream().filter(item -> !inFlight.contains(item.getId())).toList();
    }

    /** Titles on the continue-watching row, which have their own place on the screen. */
    private Set<String> inFlight(Profile profile) {
        if (profile == null) {
            return Set.of();
        }
        Set<String> started = new HashSet<>();
        for (PlaybackProgress row : progress.findByProfileId(profile.getId())) {
            if (!row.isWatched() && row.getPositionSeconds() > IN_FLIGHT_SECONDS) {
                started.add(row.getMediaItemId());
            }
        }
        return started;
    }

    private Map<String, ItemSummaryDto> summarise(Profile profile, List<Recommended> ranked) {
        List<MediaItem> entities = ranked.stream().map(Recommended::item).toList();
        Map<String, ItemSummaryDto> byId = new LinkedHashMap<>();
        for (ItemSummaryDto summary : catalog.summarise(profile, entities)) {
            byId.put(summary.id(), summary);
        }
        return byId;
    }

    /**
     * The handful of facets driving the picks, so the client can show what the server
     * thinks it knows — and the person can tell it that it is wrong.
     */
    private static List<TasteFacetDto> topFacets(TasteProfile taste) {
        return taste.weights().entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<Facet, Double>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().value()))
                .limit(8)
                .map(entry -> new TasteFacetDto(
                        entry.getKey().kind().name().toLowerCase(java.util.Locale.ROOT),
                        entry.getKey().value(),
                        entry.getValue(),
                        taste.evidenceFor(entry.getKey()).orElse(null)))
                .toList();
    }
}
