package com.example.kido.media.engagement.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.dto.HomeDtos.MirrorRailDto;
import com.example.kido.media.dto.HomeDtos.MirrorTileDto;

import lombok.extern.slf4j.Slf4j;

/**
 * "Trending on OTTVault" — what our own users are engaging with, ranked.
 *
 * <p>The mirror home already carries a "Top 10 This Week" the external feed composes; that
 * rail is what is hot on NetMirror at large. This one is different in kind: it is what is
 * hot <em>here</em>, tallied from our members' own likes, saved titles and resumes, so a
 * small audience sees its own taste reflected rather than a global chart it had no part in.
 *
 * <p>Three signals feed it, each a different weight because each is a different depth of
 * intent. A resume is intent acted on — someone pressed play and got far enough for a
 * heartbeat to land — so it counts most. A save is a deliberate "later", worth more than a
 * passing tap of the heart. A like is the lightest touch. Every signal is decayed by age on
 * a two-week half-life, so a burst of interest last week outranks a title that was busy a
 * season ago and has gone quiet: the rail reads as "trending", a derivative, not "all-time
 * popular", a total.
 *
 * <p>A like carries no title snapshot — only the bare mirror id — so it can lift a title
 * another signal already named but can never introduce one on its own. The name and poster
 * a tile draws with come from a save or a resume, which snapshot them; the freshest such
 * snapshot wins, since art drifts upstream and the most recent copy is the truest. A title
 * seen only through likes is scored but cannot be drawn, so it is dropped rather than shown
 * as a nameless gap.
 *
 * <p>This reads only; it writes nothing and needs no migration. It is deliberately
 * in-memory: the audience is small and the window short, so a few hundred rows decayed and
 * tallied in a map is cheaper and plainer than a decay expressed in SQL that two databases
 * would have to agree on.
 */
@Slf4j
@Service
public class MirrorTrendingService {

    /** A resume — play actually pressed — is the deepest signal of the three. */
    private static final double WATCH_WEIGHT = 3.0;

    /** A save is a deliberate "watch later": stronger than a like, lighter than a resume. */
    private static final double SAVE_WEIGHT = 2.0;

    /** A like is the lightest touch; it lifts a title but cannot name one. */
    private static final double LIKE_WEIGHT = 1.0;

    /** Interest halves every two weeks, so a recent burst outranks a stale favourite. */
    private static final double HALF_LIFE_DAYS = 14.0;

    /** How far back a signal still counts at all. Decay makes the far edge near-weightless. */
    private static final Duration WINDOW = Duration.ofDays(90);

    /** Below this many drawable tiles the rail is withheld — a near-empty "trending" lies. */
    private static final int MIN_TILES = 3;

    /** The rail never runs longer than this, however much signal there is. */
    private static final int MAX_TILES = 20;

    /** The stable row key the client keys its lazy row on; never shown. */
    static final String RAIL_KEY = "trending-ottvault";

    /** The heading as shown. */
    private static final String RAIL_TITLE = "Trending on OTTVault";

    private final MirrorLikeRepository likes;
    private final MirrorSavedRepository saved;
    private final MirrorProgressRepository progress;

    public MirrorTrendingService(MirrorLikeRepository likes,
                                 MirrorSavedRepository saved,
                                 MirrorProgressRepository progress) {
        this.likes = likes;
        this.saved = saved;
        this.progress = progress;
    }

    /**
     * The trending rail, or an empty rail (which the client omits) when there is not yet
     * enough signal to fill one honestly.
     */
    @Transactional(readOnly = true)
    public MirrorRailDto trending() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(WINDOW);
        Map<String, Agg> byId = new HashMap<>();

        // Likes first: they add score but seed no snapshot, so a title carried only by likes
        // stays nameless and is dropped at the end.
        for (MirrorLike like : likes.findByCreatedAtAfter(cutoff)) {
            agg(byId, like.getMirrorId()).add(LIKE_WEIGHT, decay(like.getCreatedAt(), now));
        }
        // Saves and resumes add score and name the title. Resume snapshots are the freshest,
        // so they are applied last and win ties on recency.
        for (MirrorSavedItem item : saved.findByCreatedAtAfter(cutoff)) {
            Agg a = agg(byId, item.getMirrorId());
            a.add(SAVE_WEIGHT, decay(item.getCreatedAt(), now));
            a.snapshot(item.getCreatedAt(), item.getName(), item.getPosterUrl());
        }
        for (MirrorProgress p : progress.findByUpdatedAtAfter(cutoff)) {
            Agg a = agg(byId, p.getMirrorId());
            a.add(WATCH_WEIGHT, decay(p.getUpdatedAt(), now));
            a.snapshot(p.getUpdatedAt(), p.getName(), p.getPosterUrl());
        }

        List<MirrorTileDto> tiles = byId.entrySet().stream()
                .filter(e -> e.getValue().name != null)
                .sorted(Comparator
                        .comparingDouble((Map.Entry<String, Agg> e) -> e.getValue().score).reversed()
                        .thenComparing(e -> e.getValue().snapshotAt, Comparator.reverseOrder()))
                .limit(MAX_TILES)
                .map(e -> new MirrorTileDto(e.getKey(), e.getValue().name, e.getValue().poster, null))
                .toList();

        if (tiles.size() < MIN_TILES) {
            log.debug("Trending withheld: only {} drawable tile(s), need {}", tiles.size(), MIN_TILES);
            return new MirrorRailDto(RAIL_KEY, RAIL_TITLE, List.of());
        }
        log.info("Trending on OTTVault composed from {} title(s)", tiles.size());
        return new MirrorRailDto(RAIL_KEY, RAIL_TITLE, tiles);
    }

    private static Agg agg(Map<String, Agg> byId, String mirrorId) {
        return byId.computeIfAbsent(mirrorId, k -> new Agg());
    }

    /** {@code 0.5 ^ (age / half-life)} — one at this instant, a half every two weeks back. */
    private static double decay(Instant signalAt, Instant now) {
        if (signalAt == null) {
            return 0.0;
        }
        double ageDays = Math.max(0.0, Duration.between(signalAt, now).toSeconds() / 86_400.0);
        return Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
    }

    /** One title's running tally and the snapshot it will draw with. */
    private static final class Agg {
        private double score;
        private String name;
        private String poster;
        private Instant snapshotAt = Instant.EPOCH;

        void add(double weight, double decay) {
            score += weight * decay;
        }

        /** Keep the most recent snapshot: upstream art drifts, and the latest copy is truest. */
        void snapshot(Instant at, String name, String poster) {
            if (name == null || name.isBlank()) {
                return;
            }
            if (at != null && at.isAfter(snapshotAt)) {
                this.snapshotAt = at;
                this.name = name;
                this.poster = poster;
            }
        }
    }
}
