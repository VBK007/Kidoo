package com.example.kido.media.recommend;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.downloads.MediaSettingsService;
import com.example.kido.media.downloads.ProfileMediaSettings;
import com.example.kido.media.engagement.MediaItemLikeRepository;
import com.example.kido.media.playback.PlaybackProgress;
import com.example.kido.media.playback.PlaybackProgressRepository;
import com.example.kido.media.recommend.TasteProfile.Facet;
import com.example.kido.media.recommend.TasteProfile.FacetKind;
import com.example.kido.media.session.WatchEventRepository;
import com.example.kido.profile.Profile;

/**
 * Derives what a profile likes from what it has actually done.
 *
 * <p><b>Computed per request, not stored.</b> The plan for this feature called for a
 * table, on the reasoning that watch events may one day be pruned and a derived value
 * should outlive them. That reasoning does not survive contact: a stored taste is only
 * useful while it is refreshed, and a refresh reads the same pruned events, so the table
 * buys staleness rather than durability. The real defence against pruning is the weights
 * below — a finish is the heaviest signal and lives on {@link PlaybackProgress}, which
 * nothing prunes.
 *
 * <p>Four reads and some arithmetic over a few hundred rows is cheap next to the ten
 * queries the home screen already runs, and it is never wrong.
 *
 * <p>Weights are ordered by how much each signal actually claims. Finishing something is
 * the strongest statement available. Time spent is next, and is scaled by how much of the
 * title that was, so an hour of a six-hour box set is not mistaken for devotion. A like is
 * deliberate but rare. Stated preferences count least — they are what somebody said in a
 * setup screen once, and behaviour beats it the moment there is any.
 *
 * <p>Abandonment is the only negative, and the only signal in this codebase that was
 * being recorded and never read: a title started, barely watched, and never returned to
 * is a person saying no. Without it a recommender confidently pushes the thing they
 * already rejected.
 */
@Service
public class TasteProfiler {

    /** Finished it. Nothing else a person can do says as much. */
    private static final double FINISHED = 1.0;

    /** Watched most of it without finishing — scaled by the fraction actually seen. */
    private static final double WATCHED_THROUGH = 0.8;

    /** Deliberate, but there are an order of magnitude fewer of these than finishes. */
    private static final double LIKED = 0.6;

    /** What they said before the server knew anything about them. */
    private static final double STATED = 0.3;

    /** Started, barely watched, never returned to. */
    private static final double ABANDONED = -0.5;

    /** Below this much of a title, with no return, counts as walking away. */
    private static final double ABANDONED_FRACTION = 0.15;

    /** Long enough that "I'll finish it later" is no longer the likelier explanation. */
    private static final Duration ABANDONED_AFTER = Duration.ofDays(21);

    /**
     * A facet needs this much accumulated affinity before it is a taste rather than a
     * coincidence. One film does not make somebody a fan of its lead actor.
     */
    private static final double MIN_WEIGHT = 0.35;

    private final MediaItemRepository items;
    private final PlaybackProgressRepository progress;
    private final WatchEventRepository watchEvents;
    private final MediaItemLikeRepository likes;
    private final MediaSettingsService settings;

    public TasteProfiler(MediaItemRepository items,
                         PlaybackProgressRepository progress,
                         WatchEventRepository watchEvents,
                         MediaItemLikeRepository likes,
                         MediaSettingsService settings) {
        this.items = items;
        this.progress = progress;
        this.watchEvents = watchEvents;
        this.likes = likes;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public TasteProfile forProfile(Profile profile) {
        if (profile == null) {
            return TasteProfile.empty(null);
        }

        List<PlaybackProgress> history = progress.findByProfileId(profile.getId());
        Map<String, Double> secondsByItem = secondsByItem(profile.getId());
        Set<String> liked = new HashSet<>(likes.findAllLikedItemIds(profile.getId()));

        Set<String> touched = new HashSet<>(secondsByItem.keySet());
        touched.addAll(liked);
        history.forEach(row -> touched.add(row.getMediaItemId()));
        if (touched.isEmpty()) {
            return statedOnly(profile);
        }

        Map<String, PlaybackProgress> progressByItem = new HashMap<>();
        history.forEach(row -> progressByItem.put(row.getMediaItemId(), row));

        // Raw affinity per facet, plus the title that contributed most to each.
        Map<Facet, Double> totals = new LinkedHashMap<>();
        Map<Facet, Double> best = new HashMap<>();
        Map<Facet, String> evidence = new HashMap<>();
        int counted = 0;

        Instant now = Instant.now();
        for (MediaItem item : items.findAllById(touched)) {
            double affinity = affinityFor(item, progressByItem.get(item.getId()),
                    secondsByItem.getOrDefault(item.getId(), 0.0), liked.contains(item.getId()),
                    now);
            if (affinity == 0) {
                continue;
            }
            counted++;
            for (Facet facet : TasteProfile.facetsOf(item)) {
                totals.merge(facet, affinity, Double::sum);
                if (affinity > best.getOrDefault(facet, 0.0)) {
                    best.put(facet, affinity);
                    evidence.put(facet, item.getTitle());
                }
            }
        }

        seedStatedPreferences(profile, totals);
        Map<Facet, Double> normalised = normalisePerKind(totals);
        evidence.keySet().retainAll(normalised.keySet());

        return new TasteProfile(profile.getId(), normalised, Map.copyOf(evidence), counted);
    }

    /**
     * What one title says about this person.
     *
     * <p>The signals add rather than override: somebody who finished a film *and* liked
     * it has said more than somebody who only finished it, and the ranking should reflect
     * that.
     */
    private static double affinityFor(MediaItem item,
                                      PlaybackProgress progress,
                                      double secondsWatched,
                                      boolean liked,
                                      Instant now) {
        double affinity = 0;
        if (liked) {
            affinity += LIKED;
        }
        if (progress != null && progress.isWatched()) {
            return affinity + FINISHED;
        }

        Double runtimeSeconds = runtimeSecondsOf(item, progress);
        double fraction = runtimeSeconds == null || runtimeSeconds <= 0
                ? 0
                : Math.min(1.0, secondsWatched / runtimeSeconds);

        if (fraction >= ABANDONED_FRACTION) {
            return affinity + WATCHED_THROUGH * fraction;
        }
        // Barely started. Only a verdict once they have had time to come back.
        boolean staleEnough = progress != null && progress.getUpdatedAt() != null
                && progress.getUpdatedAt().isBefore(now.minus(ABANDONED_AFTER));
        if (staleEnough && !liked) {
            return ABANDONED;
        }
        return affinity;
    }

    /** The progress row's duration is what the player measured; the catalog's is a guess. */
    private static Double runtimeSecondsOf(MediaItem item, PlaybackProgress progress) {
        if (progress != null && progress.getDurationSeconds() != null
                && progress.getDurationSeconds() > 0) {
            return progress.getDurationSeconds();
        }
        return item.getRuntimeMinutes() == null ? null : item.getRuntimeMinutes() * 60.0;
    }

    /**
     * The first-run answers, at a weight behaviour easily beats.
     *
     * <p>They are why a profile that has watched nothing still gets a sensible first
     * screen — which is the entire reason those answers were persisted to the profile
     * rather than left on the handset.
     */
    private void seedStatedPreferences(Profile profile, Map<Facet, Double> totals) {
        ProfileMediaSettings stated = settings.forProfile(profile);
        if (stated == null) {
            return;
        }
        for (String genre : stated.getPreferredGenres()) {
            totals.merge(new Facet(FacetKind.GENRE, genre.toLowerCase(Locale.ROOT)),
                    STATED, Double::sum);
        }
        if (stated.getPreferredLanguage() != null) {
            totals.merge(new Facet(FacetKind.LANGUAGE,
                            stated.getPreferredLanguage().toLowerCase(Locale.ROOT)),
                    STATED, Double::sum);
        }
    }

    /** A profile with no history at all: whatever it said it wanted, and nothing else. */
    private TasteProfile statedOnly(Profile profile) {
        Map<Facet, Double> totals = new LinkedHashMap<>();
        seedStatedPreferences(profile, totals);
        return totals.isEmpty()
                ? TasteProfile.empty(profile.getId())
                : new TasteProfile(profile.getId(), normalisePerKind(totals), Map.of(), 0);
    }

    /**
     * Each kind against its own maximum, so a genre weight and a language weight are
     * comparable.
     *
     * <p>Per kind and not globally, because the kinds are not on the same scale: everybody
     * has a most-watched language and it would sit at 1.0, flattening every genre against
     * it. The same reasoning as {@code PopularityRanker} normalising each signal against
     * the library's own maximum rather than across signals.
     */
    private static Map<Facet, Double> normalisePerKind(Map<Facet, Double> totals) {
        Map<FacetKind, Double> maxima = new HashMap<>();
        for (Map.Entry<Facet, Double> entry : totals.entrySet()) {
            if (entry.getValue() > 0) {
                maxima.merge(entry.getKey().kind(), entry.getValue(), Math::max);
            }
        }

        Map<Facet, Double> normalised = new LinkedHashMap<>();
        for (Map.Entry<Facet, Double> entry : totals.entrySet()) {
            double max = maxima.getOrDefault(entry.getKey().kind(), 0.0);
            if (max <= 0) {
                continue;
            }
            double weight = entry.getValue() / max;
            // Negatives are kept: a facet somebody has walked away from should push a
            // title down, not merely fail to push it up.
            if (weight >= MIN_WEIGHT || weight < 0) {
                normalised.put(entry.getKey(), round(weight));
            }
        }
        return Map.copyOf(normalised);
    }

    private Map<String, Double> secondsByItem(String profileId) {
        Map<String, Double> seconds = new HashMap<>();
        for (Object[] row : watchEvents.secondsByItemForProfile(profileId)) {
            seconds.put((String) row[0], ((Number) row[1]).doubleValue());
        }
        return seconds;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
