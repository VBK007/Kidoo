package com.example.kido.mymirror.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Callable;
import java.time.Duration;
import com.example.kido.mymirror.MirrorRequestContext;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import com.example.kido.common.ApiException;
import com.example.kido.media.dto.HomeDtos.MirrorRailDto;
import com.example.kido.media.dto.HomeDtos.MirrorTileDto;
import com.example.kido.mymirror.catalogue.MirrorCatalogueStore;
import com.example.kido.mymirror.ClientHeaderSupport;
import com.example.kido.mymirror.MirrorHomeParser;
import com.example.kido.mymirror.MirrorHomeParser.Title;
import com.example.kido.mymirror.MirrorCloudflare;
import com.example.kido.mymirror.MirrorOtt;
import com.example.kido.mymirror.MirrorRequestLimit;
import com.example.kido.mymirror.MirrorSessionProvider;
import com.example.kido.mymirror.MirrorStreamSigner;
import com.example.kido.mymirror.manager.TokenManager;
import com.example.kido.mymirror.model.ClientHeaders;
import com.example.kido.mymirror.repo.BrowseRepository;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mirror's home page joined with playlist.php, as the app's home-screen rails.
 *
 * <p>The home screen must draw whether or not the mirror is up, so nothing here throws: any
 * failure means no rails. The rails mirror the whole feed, not just its first screen, grouped by
 * the home's own categories (Netflix's rail headings, Prime's "Top 10 …" rows); tiles outside any
 * category fold into a single rail. Only
 * the first few playlists are fetched on the home load ({@link #EAGER_PLAYLISTS}), within a fixed
 * budget and across the whole grid rather than per rail; every other tile is sent without a
 * playlist and the client fetches it on tap.
 */
@Slf4j
@Service
public class MirrorHomeService {
    // At most this many tiles PER rail. The feed is the real content ceiling (its categories run
    // ~3-20 tiles each); this only trims a pathologically deep one. Applied per category, not across
    // the whole grid, so a feed laid out category-by-category (Prime's homepage.php lists one
    // category's tiles before the next) cannot let its first categories soak every slot and leave
    // the rest a lone tile each — the one-tile-per-rail symptom the old whole-grid cap produced.
    public static final int TILES_PER_RAIL = 20;
    // A whole-grid safety cap, far above any real feed (~28 categories x ~14 tiles = ~380), so a
    // runaway feed cannot allocate without bound. Not the per-screen limit the old flat cap was.
    public static final int MAX_GRID_TILES = 500;
    // How many tiles get their playlist pre-fetched on the home load. ZERO on purpose: net52
    // rate-limits this app's address on the streaming path (playlist.php/playback), and it does so
    // aggressively enough that pre-warming a dozen playlists on every home build — which recurs on the
    // cache TTL and so fires 60+ streaming-path calls across a browsing session — trips the limit
    // within minutes. Once tripped, EVERY subsequent play is answered with net52's placeholder
    // ("STOP ABUSE" asset 220884 / in=unknown) instead of a real CDN source, so a latency shortcut on
    // the home screen was silently breaking playback for the whole app. Proven with a single title
    // (82095535): a real fill right after an overnight-cleared limit, then the same id penalised ~35min
    // later once the home-load fan-out had re-tripped it. The playlist is only ever needed on an
    // explicit tap, and the client already fetches it then, so the home now touches net52's streaming
    // path zero times — it costs one playlist round-trip on first tap, which is a fraction of a second
    // and far cheaper than rate-limiting the app into total playback failure.
    public static final int EAGER_PLAYLISTS = 0;
    private static final long BUDGET_MILLIS = 4000;
    // Composing the Netflix home (see composeNetflixFeed): how many trending titles seed a "More Like
    // <title>" rail from their post.php suggest graph, and how long to wait on those seed fetches
    // before serving whatever rails are ready. Kept small — the seeds fetch in parallel and the rest
    // of the budget (BUDGET_MILLIS) goes to naming the suggested tiles, which carry ids only.
    private static final int RELATED_SEEDS = 5;
    private static final long COMPOSE_BUDGET_MILLIS = 3000;
    // A composed genre rail (see composeNetflixFeed) is shown only once it holds at least this many
    // tiles, so a genre that is barely represented in the trending pool (Anime on an India-weighted
    // day, say) is hidden rather than padded out into a one- or two-tile rail. It surfaces honestly on
    // the days it actually trends and the cache has filled, never faked.
    private static final int MIN_GENRE_TILES = 3;
    // The genre rails draw from a pool of titles whose genre is known, and the ~20 trending titles
    // alone fill only the two or three commonest buckets (Crime, Comedies) — so Anime, Horror, Sci-Fi
    // and the rest stay hidden on a cold cache even though the suggest graph around the trending
    // titles is full of them. Before building the rails, composeNetflixFeed fetches the genre of up to
    // this many suggested ids it has not cached yet (deduped against the cache, within one extra
    // COMPOSE_BUDGET_MILLIS window, degrading to whatever finished) so the pool is wide enough to
    // surface many categories on the FIRST load rather than only after several polls have warmed the
    // name cache. Cached genres are reused, so this falls to near zero once the catalogue is known.
    private static final int GENRE_POOL_FETCH_MAX = 48;
    // Some whole categories never ride the trending/suggest graph. Anime and children's cartoons do not
    // chart on an India-weighted Top Searches feed, nor sit in the suggest graph around what does, so
    // however wide we warm that graph the Anime and Cartoons rails stay permanently empty — the titles
    // simply are not reachable from the trending seeds. The only feed that reaches them is search.php,
    // a title text search, so we seed those rails directly from a curated set of franchise queries
    // (search names every hit inline, unlike the suggest graph). Run once per process: the discovered
    // ids and their genres persist in seedPool/genreCache, so every later compose folds seedPool into
    // the pool for free and never searches again. Capped so the one-time discovery stays bounded.
    private static final int SEED_POOL_MAX = 120;
    // Take only the first few hits of each query. A single franchise search can return 50 near-identical
    // sequels (One Piece, Dragon Ball), which would fill the whole seed pool before the later queries ran
    // and starve the cartoon rail; capping per query keeps every franchise represented.
    private static final int SEED_HITS_PER_QUERY = 6;
    private static final List<String> SEED_QUERIES = List.of(
            "Naruto", "One Piece", "Demon Slayer", "Jujutsu Kaisen", "Dragon Ball", "Attack on Titan",
            "Death Note", "My Hero Academia", "Bleach", "Spy x Family", "Solo Leveling", "Pokemon",
            "Chhota Bheem", "Doraemon", "Motu Patlu", "Shinchan", "Little Singham", "Ben 10");
    // Hotstar (dp) has NO home or trending route upstream (every /mobile/hs home candidate 404s —
    // confirmed live 2026-11-23), so its catalogue is entered entirely through these curated title
    // searches, the one feed that reaches it. Unlike SEED_QUERIES (which only tops up Netflix's
    // anime/cartoon rails on top of a live trending feed), this IS the Hotstar home's content source,
    // so it spans the catalogue's verticals — Hollywood/Disney/Marvel, Indian blockbusters across
    // languages, Hotstar Specials, and kids — roughly popularity-ordered so the lead hits seed the
    // "Popular on Hotstar" rail. Each search names its hits inline; genres and suggest graphs are then
    // read from each title's hs/post.php detail, exactly as the Netflix compose does. Run once per
    // process (hsSeedsSearched); the discovered ids/names/genres persist in hsSeedPool/nameCache/
    // genreCache, so every later compose is free.
    private static final List<String> HS_SEED_QUERIES = List.of(
            "Avatar", "Avengers", "Spider-Man", "Deadpool", "Star Wars", "The Lion King", "Frozen",
            "Moana", "Toy Story", "Salaar", "Pushpa", "Jawan", "Pathaan", "Animal", "Kalki", "Leo",
            "RRR", "Bahubali", "Stree", "Kantara", "Aarya", "Special Ops", "The Night Manager",
            "Criminal Justice", "Rana Naidu", "Shogun", "Doraemon", "Shinchan");
    // How many seed titles get their hs/post.php detail (genre + suggest graph) fetched on a compose,
    // mirroring the ~20 trending details the Netflix compose fetches. The rest of hsSeedPool still
    // buckets into the genre rails as its genre fills over loads (the warm loop tops up leftover seeds).
    private static final int HS_PRIMARY_SEEDS = 20;
    // A build that named every tile holds for the full window; one that ran out of budget with tiles
    // still unnamed holds only briefly, so once the background pre-warm (prewarmNames) has filled the
    // name cache the next poll rebuilds a full grid instead of serving the thin one for the whole window.
    private static final long CACHE_MILLIS = 5 * 60 * 1000;
    private static final long PARTIAL_CACHE_MILLIS = 60 * 1000;
    // An id -> title cache. A mirror title's name never changes, so this never needs invalidating; it
    // lets the rebuilds (and other OTTs sharing an id) fill rails without re-hitting post.php, and it
    // is what the background pre-warm populates. Bounded so it cannot grow without limit.
    private static final int NAME_CACHE_MAX = 10000;

    private final BrowseRepository browseRepository;
    private final PlaybackService playbackService;
    private final DispatcherService dispatcherService;
    private final TokenManager tokenManager;
    private final MirrorSessionProvider sessionProvider;
    private final MirrorCatalogueStore catalogueStore;
    private final MirrorStreamSigner signer;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    // A few at a time: a burst of simultaneous calls per home screen would look like a flood
    // upstream, so every name and playlist lookup goes through this bounded pool.
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon().name("mirror-catalogue-", 0).factory());

    private final ThreadPoolExecutor refreshWorker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>(), Thread.ofPlatform().daemon().name("mirror-refresh-", 0).factory());

    private record HomeKey(String cookie, long generation) {}
    private record Cached(List<MirrorRailDto> rails, long until) {}
    private final ConcurrentHashMap<HomeKey, Cached> byCookie = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HomeKey, CompletableFuture<List<MirrorRailDto>>> builds = new ConcurrentHashMap<>();

    private <T> Future<T> submit(Callable<T> task) {
        long deadline = MirrorRequestContext.deadlineWithin(Duration.ofSeconds(8));
        var budget = MirrorRequestContext.backgroundBudget();
        try {
            return pool.submit(() -> {
                if (System.nanoTime() >= deadline) return null;
                return MirrorRequestContext.withinDeadline(deadline,
                        () -> MirrorRequestContext.background(budget, () -> MirrorRequestContext.optional(task)));
            });
        } catch (RejectedExecutionException ex) {
            io.micrometer.core.instrument.Metrics.counter("mirror.catalogue.rejected").increment();
            return CompletableFuture.completedFuture(null);
        }
    }

    // The rails plus whether the build named every selected tile within the budget; a partial build is
    // cached only briefly (see PARTIAL_CACHE_MILLIS) so it is rebuilt once the pre-warm has caught up.
    private record Build(List<MirrorRailDto> rails, boolean complete) {}

    private static final class CatalogueData {
        private final ConcurrentHashMap<String, String> nameCache = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, String> genreCache = new ConcurrentHashMap<>();
        private final Set<String> seedPool = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean seedsSearched = new AtomicBoolean(false);
        private final Set<String> hsSeedPool = Collections.synchronizedSet(new LinkedHashSet<>());
        private final AtomicBoolean hsSeedsSearched = new AtomicBoolean(false);
    }

    private final LinkedHashMap<HomeKey, CatalogueData> catalogues = new LinkedHashMap<>(16, .75f, true);

    private synchronized CatalogueData catalogueData(HomeKey key) {
        CatalogueData data = catalogues.get(key);
        if (data == null) {
            if (catalogues.size() >= 32) catalogues.pollFirstEntry();
            data = new CatalogueData();
            catalogues.put(key, data);
        }
        return data;
    }

    // Netflix's post.php `genre` taxonomy folded into a handful of display rails. The order is
    // ASSIGNMENT PRIORITY, not display order: a title joins the FIRST bucket whose genre string
    // contains one of the listed (lower-cased) substrings, so a Crime Thriller lands in the more
    // distinctive "Crime" rail and the broad "Dramas" catch-all sits last and only catches what
    // nothing else did. Substrings validated against the live catalogue (genre-distribution probe,
    // 2026-11-22). Adding a bucket here is all it takes to add a rail.
    private record GenreBucket(String label, List<String> substrings) {}
    private static final List<GenreBucket> GENRE_BUCKETS = List.of(
            // Anime first: every anime genre string ("Shounen Anime", "Action Anime", "Anime Series",
            // "Drama Anime", "Anime Features", ...) contains "anime", so an anime title lands here even
            // when it also reads as Kids' TV or Sci-Fi. Cartoons next: Netflix tags these "TV Cartoons"
            // (Chhota Bheem, LEGO, Motu Patlu), which nothing else below matches. Both verified against
            // the live catalogue (anime/cartoon search probe, 2026-11-22).
            new GenreBucket("Anime", List.of("anime")),
            new GenreBucket("Cartoons", List.of("cartoon")),
            // Hotstar tags its Disney/Pixar/animated catalogue "Animation" (not Netflix's "anime"/
            // "cartoon"), so without this bucket every Hotstar animated title falls through to a
            // catch-all. Placed after Anime/Cartoons so a title tagged both still prefers those. Harmless
            // for Netflix (its taxonomy rarely uses the bare word "animation"); seen live 2026-11-23.
            new GenreBucket("Animation", List.of("animation")),
            new GenreBucket("Horror", List.of("horror")),
            new GenreBucket("Documentaries", List.of("documentar", "docuseries", "docudrama")),
            new GenreBucket("Stand-Up & Talk", List.of("stand-up", "talk show", "variety")),
            new GenreBucket("Kids & Family", List.of("kids", "children", "family watch", "family features", "family time")),
            new GenreBucket("Sci-Fi & Fantasy", List.of("sci-fi", "science fiction", "fantasy")),
            new GenreBucket("Romance", List.of("romantic", "romance", "k-drama")),
            new GenreBucket("Comedies", List.of("comedies", "sitcom", "comedy")),
            new GenreBucket("Crime", List.of("crime")),
            new GenreBucket("Mysteries", List.of("mystery", "mysteries")),
            new GenreBucket("Thrillers", List.of("thriller")),
            new GenreBucket("Action & Adventure", List.of("action", "adventure")),
            new GenreBucket("Dramas", List.of("dramas", "drama")));

    // The single best rail for a genre string, or null if none of the buckets match (or the genre is
    // unknown). First match wins, so GENRE_BUCKETS order is the priority.
    private static String bestBucket(String genre) {
        if (genre == null || genre.isBlank()) return null;
        String g = genre.toLowerCase(Locale.ROOT);
        for (GenreBucket bucket : GENRE_BUCKETS) {
            for (String sub : bucket.substrings()) {
                if (g.contains(sub)) return bucket.label();
            }
        }
        return null;
    }

    // Cache a title's genre from its post.php detail, mirroring nameCache's bound. putIfAbsent so a
    // concurrent reader never clobbers an entry, and the size guard keeps it from growing without limit.
    private void cacheGenre(CatalogueData data, String id, JsonNode detail) {
        String genre = detail.path("genre").asString("").trim();
        if (!genre.isEmpty() && data.genreCache.size() < NAME_CACHE_MAX) data.genreCache.putIfAbsent(id, genre);
    }

    public MirrorHomeService(BrowseRepository browseRepository, PlaybackService playbackService,
                             DispatcherService dispatcherService, TokenManager tokenManager,
                             MirrorSessionProvider sessionProvider, MirrorCatalogueStore catalogueStore,
                             MirrorStreamSigner signer) {
        this.browseRepository = browseRepository;
        this.playbackService = playbackService;
        this.dispatcherService = dispatcherService;
        this.tokenManager = tokenManager;
        this.sessionProvider = sessionProvider;
        this.catalogueStore = catalogueStore;
        this.signer = signer;
    }

    public List<MirrorRailDto> rail(String cookie) {
        return rail(cookie, null);
    }

    /**
     * The rails for one OTT catalogue ({@code nf}/{@code pv}/{@code dp}, see {@link MirrorOtt}):
     * one per feed category, or a single rail where the feed has none, and empty when there is
     * nothing to show. The catalogue is folded into the cookie, so the cache below keys each apart.
     */
    public List<MirrorRailDto> rail(String cookie, String ott) {
        return railWithStatus(cookie, ott).rails();
    }

    public record RailResult(List<MirrorRailDto> rails, String state) {}

    public RailResult railWithStatus(String cookie, String ott) {
        String effective = MirrorOtt.apply((cookie != null && !cookie.isBlank())
                ? cookie : sessionProvider.currentCookie(), ott);
        if (effective == null || effective.isBlank()) {
            return new RailResult(List.of(), sessionProvider.state() == MirrorSessionProvider.State.MISSING
                    ? "missing" : "unavailable");
        }
        try {
            List<MirrorRailDto> rails = loadRail(effective);
            return new RailResult(rails, rails.isEmpty() ? "empty" : "available");
        } catch (RuntimeException ex) {
            log.debug("Mirror catalogue unavailable: {}", ex.getClass().getSimpleName());
            return new RailResult(List.of(), "unavailable");
        }
    }

    /**
     * A cache-only read of the rails for the saturation fallback: the warm catalogue with no upstream
     * work and no exceptions. It consults L1 (the in-memory per-cookie cache) and then, only when
     * persistence is enabled, L2 (the last-known-good snapshot). It never composes, never hits the
     * mirror and never throws, so a Home request whose optional worker slot is unavailable can still
     * answer with the last good catalogue instead of an empty one.
     *
     * <p>State reflects where the rails came from: {@code cached} (fresh L1 or L2), {@code stale}
     * (served past the soft TTL), {@code partial} (a snapshot that was never completed), or {@code
     * unavailable}/{@code missing} when nothing warm is on hand.
     */
    public RailResult warmRails(String cookie, String ott) {
        String effective = MirrorOtt.apply((cookie != null && !cookie.isBlank())
                ? cookie : sessionProvider.currentCookie(), ott);
        if (effective == null || effective.isBlank()) {
            return new RailResult(List.of(), sessionProvider.state() == MirrorSessionProvider.State.MISSING
                    ? "missing" : "unavailable");
        }
        try {
            HomeKey key = new HomeKey(effective, sessionProvider.generation(effective));
            long now = System.currentTimeMillis();
            Cached cached = byCookie.get(key);
            if (cached != null && now < cached.until() && !cached.rails().isEmpty()) {
                sessionProvider.ifCurrent(effective, key.generation(), () -> {});
                return new RailResult(cached.rails(), "cached");
            }
            String namespace = catalogueNamespace(key);
            if (namespace != null) {
                var view = catalogueStore.readInNamespace(namespace, MirrorOtt.of(effective));
                if (view.isPresent() && !view.get().rails().isEmpty()) {
                    sessionProvider.ifCurrent(effective, key.generation(), () -> {});
                    String state = switch (view.get().freshness()) {
                        case FRESH -> "cached";
                        case STALE -> "stale";
                        case PARTIAL -> "partial";
                    };
                    return new RailResult(view.get().rails(), state);
                }
            }
        } catch (RuntimeException ex) {
            log.debug("Mirror warm read unavailable: {}", ex.getClass().getSimpleName());
        }
        return new RailResult(List.of(), "unavailable");
    }

    private List<MirrorRailDto> loadRail(String effective) {
        HomeKey key = new HomeKey(effective, sessionProvider.generation(effective));
        long now = System.currentTimeMillis();
        Cached cached = byCookie.get(key);
        if (cached != null && now < cached.until()) {
            io.micrometer.core.instrument.Metrics.counter("mirror.home.cache", "outcome", "hit").increment();
            sessionProvider.ifCurrent(effective, key.generation(), () -> {});
            return cached.rails();
        }
        io.micrometer.core.instrument.Metrics.counter("mirror.home.cache", "outcome", "miss").increment();
        var owned = new CompletableFuture<List<MirrorRailDto>>();
        var running = builds.putIfAbsent(key, owned);
        if (running != null) {
            try {
                long remaining = MirrorRequestContext.deadlineWithin(Duration.ofSeconds(8)) - System.nanoTime();
                var rails = running.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
                sessionProvider.ifCurrent(effective, key.generation(), () -> {});
                return rails;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MirrorRequestLimit.Busy();
            } catch (ExecutionException | TimeoutException ex) {
                throw new MirrorRequestLimit.Busy();
            }
        }
        try {
            cached = byCookie.get(key);
            if (cached != null && now < cached.until()) {
                var existing = cached.rails();
                sessionProvider.ifCurrent(effective, key.generation(), () -> owned.complete(existing));
                return existing;
            }
            // Taken when the build starts, so a slow build that began earlier can never publish over
            // a newer catalogue that finished first. Returns 0 (a no-op token) when persistence is off.
            String namespace = catalogueNamespace(key);
            long buildSeq = namespace == null ? 0 : catalogueStore.nextSequence();
            Build built = build(effective, catalogueData(key));
            long ttl = built.complete() ? CACHE_MILLIS : PARTIAL_CACHE_MILLIS;
            sessionProvider.ifCurrent(effective, key.generation(), () -> {
                byCookie.values().removeIf(c -> c.until() <= now);
                if (!built.rails().isEmpty() && byCookie.size() < 1024) {
                    byCookie.put(key, new Cached(built.rails(), now + ttl));
                }
                owned.complete(built.rails());
            });
            // A late commit stays in its original namespace. Neither JDBC nor transaction
            // completion may hold the monitor used by playback generation lookups.
            if (namespace != null && !built.rails().isEmpty()) {
                try {
                    catalogueStore.publishInNamespace(namespace, MirrorOtt.of(effective), built.rails(),
                            built.complete(), buildSeq, () -> isCurrent(key));
                } catch (RuntimeException ex) {
                    log.debug("Mirror catalogue publish skipped: {}", ex.getClass().getSimpleName());
                }
            }
            sessionProvider.ifCurrent(effective, key.generation(), () -> {});
            return built.rails();
        } catch (RuntimeException | Error ex) {
            owned.completeExceptionally(ex);
            throw ex;
        } finally {
            builds.remove(key, owned);
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(
            initialDelayString = "${app.mymirror.catalogue.background-refresh.interval:1h}",
            fixedDelayString = "${app.mymirror.catalogue.background-refresh.interval:1h}")
    void scheduleBackgroundRefresh() {
        var policy = catalogueStore.backgroundRefreshPolicy();
        if (!catalogueStore.persistenceEnabled() || !policy.configured()) return;
        try {
            refreshWorker.execute(() -> {
                if (sessionProvider.state() != MirrorSessionProvider.State.VALID
                        && sessionProvider.state() != MirrorSessionProvider.State.VERIFIED) return;
                String cookie = sessionProvider.currentCookie();
                if (cookie == null || "cached".equals(warmRails(cookie, policy.getOtt()).state())) return;
                long deadline = MirrorRequestContext.deadlineWithin(Duration.ofSeconds(3));
                try {
                    MirrorRequestContext.withinDeadline(deadline, () ->
                            MirrorRequestContext.background(new java.util.concurrent.atomic.AtomicInteger(
                                    policy.getMaxRequestsPerRun()), () -> railWithStatus(cookie, policy.getOtt())));
                } catch (Exception ex) {
                    log.debug("Catalogue refresh deferred: {}", ex.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException ex) {
            log.debug("Catalogue refresh already running");
        }
    }

    private boolean isCurrent(HomeKey key) {
        try {
            return sessionProvider.generation(key.cookie()) == key.generation();
        } catch (com.example.kido.common.ApiException ex) {
            return false;
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(
            initialDelayString = "${app.mymirror.catalogue.retention-interval:1h}",
            fixedDelayString = "${app.mymirror.catalogue.retention-interval:1h}")
    void retireObsoleteCatalogues() {
        if (!catalogueStore.persistenceEnabled()) return;
        String cookie = sessionProvider.currentCookie();
        if (cookie == null) return;
        try {
            var key = new HomeKey(cookie, sessionProvider.generation(cookie));
            catalogueStore.retireInactiveNamespaces(catalogueNamespace(key), () -> isCurrent(key));
        } catch (RuntimeException ex) {
            log.debug("Catalogue retention deferred: {}", ex.getClass().getSimpleName());
        }
    }

    private String catalogueNamespace(HomeKey key) {
        if (!catalogueStore.persistenceEnabled()) return null;
        String epoch = sessionProvider.catalogueEpoch(key.cookie(), key.generation());
        if (epoch == null) return null;
        return catalogueStore.namespace(epoch, signer);
    }

    private Build build(String cookie, CatalogueData data) {
        // The t token for the tile-name lookups below, read straight from the session cookie via
        // playbackT — exactly as BrowseService does for detail/search/episodes. The home feed and
        // the post.php name lookups are catalog metadata, authorized by the session cookie, not by
        // the per-view ad-gate. getT would run verify2.php and throw 412 on the gate's normal
        // resting (c:n) state, which dropped EVERY rail off the home screen ("Mirror rails omitted:
        // the ad-gate is not cleared") even while the cookie still serves content and plays fine —
        // i.e. "mirror not connected" for a perfectly good session. playbackT returns the identical
        // t without that gate round-trip; t is only the tm= analytics value, which the mirror
        // accepts empty anyway (see TokenManager.playbackT).
        String t = tokenManager.playbackT(cookie);

        List<Title> parsed = feedTitles(data, cookie, t);

        // Group the feed into its categories in first-seen order. Prime's homepage.php lays the feed
        // out category-by-category, so selecting tiles in feed order (as the old whole-grid cap did)
        // let the first categories soak every slot and left the rest a single tile each. Grouping
        // first lets the selection below spread fairly across rails.
        LinkedHashMap<String, List<Title>> byCategory = new LinkedHashMap<>();
        for (Title ti : parsed) {
            byCategory.computeIfAbsent(categoryKey(ti.category()), k -> new ArrayList<>()).add(ti);
        }

        // Round-robin selection: pass p takes the p-th tile of every category, so a budget that only
        // reaches some tiles yields an even few-across-every-rail rather than a few full rails and the
        // rest empty. Each rail is held to TILES_PER_RAIL and the whole grid to MAX_GRID_TILES. The
        // selected order (lead tile of every rail, then the second of every rail, ...) is what group()
        // below buckets back into per-category rails, preserving feed order within each.
        List<Title> selected = new ArrayList<>();
        for (int pass = 0; pass < TILES_PER_RAIL && selected.size() < MAX_GRID_TILES; pass++) {
            for (List<Title> catTiles : byCategory.values()) {
                if (pass < catTiles.size()) {
                    selected.add(catTiles.get(pass));
                    if (selected.size() >= MAX_GRID_TILES) break;
                }
            }
        }

        // The caller's device headers, copied now: a worker may still be running after the
        // request it serves has completed.
        ClientHeaders client = ClientHeaderSupport.current();
        long deadline = System.currentTimeMillis() + BUDGET_MILLIS;

        // Phase 1 — a name for every selected tile. The client needs one both to render the tile and
        // to ask playlist.php on tap, so a tile we cannot name is not shown. Neither home feed carries
        // names (Netflix's /mobile/home?app=1 and Prime's /pv/homepage.php both give ids only), so
        // every name is looked up (post.php, cached by id) across the shared budget, in round-robin
        // order so a tight budget spreads names across rails rather than filling the first few. A tile
        // whose lookup has not finished when the budget runs out is deferred to the background pre-warm
        // and the build reported partial, so the next poll rebuilds it from the cache; a tile that
        // resolved to no name is simply dropped and not retried.
        List<Future<String>> names = new ArrayList<>();
        for (Title title : selected) {
            names.add(submit(() -> ClientHeaderSupport.callAs(client,
                    () -> title.title() != null ? title.title() : nameOf(data, title.id(), t, cookie))));
        }
        List<MirrorTileDto> tiles = new ArrayList<>();
        // The feed category of each surviving tile, kept aligned with tiles (phase 2 only replaces
        // entries in place), so grouping below reads each tile's category by index.
        List<String> categories = new ArrayList<>();
        boolean complete = true;
        for (int i = 0; i < selected.size(); i++) {
            Title title = selected.get(i);
            Future<String> future = names.get(i);
            String name = null;
            boolean resolved = false;
            try {
                name = future.get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                resolved = true;
            } catch (TimeoutException e) {
                future.cancel(true); // budget spent before this lookup finished
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
            } catch (ExecutionException e) {
                resolved = true; // the lookup ran and failed — treat as nameless, do not retry
            }
            if (!resolved) {
                // Budget ran out before this name came back. Hiding the tile is what
                // collapsed a cold Prime rail to the two or three names that beat the
                // deadline. Instead ship it id-only (null name): the client rebuilds
                // its poster from the id and plays it from the id alone — playback
                // never needs the name — and the real name fills in on the next poll,
                // once the background pre-warm below has cached it. Still queued for
                // that pre-warm and the build reported partial so the poll happens.
                tiles.add(new MirrorTileDto(title.id(), null, title.image(), null));
                categories.add(title.category());
                complete = false;
                continue;
            }
            if (name == null) continue; // resolved but no name — drop, and do not pre-warm
            tiles.add(new MirrorTileDto(title.id(), name, title.image(), null));
            categories.add(title.category());
        }

        // Phase 2 — pre-warm the first few playlists so the top of the grid plays without a round
        // trip; the rest are sent without one and the client fetches each on tap. The cap is across
        // the whole grid, not per rail, and the tiles are in round-robin order, so the warmed tiles
        // are the lead tile of the first EAGER_PLAYLISTS rails. Skipped once phase 1 has spent the
        // budget, so a slow grid never trades its tiles for playlists.
        int warm = Math.min(tiles.size(), EAGER_PLAYLISTS);
        if (warm > 0 && System.currentTimeMillis() < deadline) {
            List<Future<JsonNode>> playlists = new ArrayList<>();
            for (int i = 0; i < warm; i++) {
                MirrorTileDto tile = tiles.get(i);
                playlists.add(submit(() -> ClientHeaderSupport.callAs(client, () -> {
                    try {
                        return mapper.readTree(playbackService.playlist(tile.id(), tile.title(), cookie));
                    } catch (RuntimeException e) {
                        log.debug("No playlist for mirror title {}: {}", tile.id(), e.getClass().getSimpleName());
                        return null;
                    }
                })));
            }
            for (int i = 0; i < warm; i++) {
                JsonNode playlist = await(playlists.get(i), deadline, tiles.get(i).id());
                if (playlist != null) {
                    MirrorTileDto tile = tiles.get(i);
                    tiles.set(i, new MirrorTileDto(tile.id(), tile.title(), tile.image(), playlist));
                }
            }
        }



        return new Build(group(tiles, categories), complete);
    }

    /**
     * The tiles as rails: one per feed category, in first-seen order, keyed by the category's slug;
     * tiles the home left uncategorised (Prime's featured slider, or a Netflix tile above the first
     * heading) fall together into the single {@code mirror} / "From the mirror" rail. So a home with
     * no categories stays one rail, and a categorised one becomes a rail per heading, with any
     * uncategorised strip first. {@code categories} is aligned index-for-index with {@code tiles}.
     */
    private static List<MirrorRailDto> group(List<MirrorTileDto> tiles, List<String> categories) {
        LinkedHashMap<String, List<MirrorTileDto>> byCategory = new LinkedHashMap<>();
        for (int i = 0; i < tiles.size(); i++) {
            byCategory.computeIfAbsent(categoryKey(categories.get(i)), k -> new ArrayList<>())
                    .add(tiles.get(i));
        }
        List<MirrorRailDto> rails = new ArrayList<>();
        byCategory.forEach((category, items) -> rails.add(category.isEmpty()
                ? new MirrorRailDto("mirror", "From the mirror", items)
                : new MirrorRailDto(slug(category), category, items)));
        return rails;
    }

    // The feed category normalised to the bucket a tile groups by: null or blank — Prime's featured
    // slider, a Netflix tile above the first heading — is the one uncategorised ("mirror") bucket, everything else its
    // trimmed name. The cap below and group() above must agree on this so "a category" means one rail.
    private static String categoryKey(String category) {
        return (category == null || category.isBlank()) ? "" : category.trim();
    }

    // A category name as a stable rail key: lower-cased, runs of non-alphanumerics folded to one
    // '-' and the ends trimmed. A name of only punctuation slugs to empty and falls back to "mirror".
    private static String slug(String category) {
        String slug = category.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "mirror" : slug;
    }

    private String nameOf(CatalogueData data, String id, String t, String cookie) {
        String cached = data.nameCache.get(id);
        if (cached != null) return cached;
        try {
            JsonNode detail = mapper.readTree(
                    browseRepository.getDetail(dispatcherService.resolveActiveBaseUrl(), id, t, cookie));
            // The same detail carries the genre; cache it now so the genre rails fill from every name
            // lookup the build and its background pre-warm already make — no extra upstream call.
            cacheGenre(data, id, detail);
            String name = detail.path("title").asString("").trim();
            if (name.isEmpty()) return null;
            // A title is immutable, so the cache never needs invalidating; bound it so a long-running
            // process browsing many catalogues cannot grow it without limit.
            if (data.nameCache.size() < NAME_CACHE_MAX) data.nameCache.putIfAbsent(id, name);
            return name;
        } catch (RuntimeException e) {
            log.debug("No name for mirror title {}: {}", id, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The home catalogue, with one retry on a freshly resolved backend. The decoded origin is
     * cached for a few minutes while the backend rotates hosts (net52 → net77), so a transient
     * fetch failure may just mean the cache points at a host that stopped serving; dropping the
     * cache and re-resolving once recovers that case. A 4xx (a 412 from the gate, say) is not a
     * rotation and is re-thrown for {@link #rail} to classify.
     */
    private List<Title> feedTitles(CatalogueData data, String cookie, String t) {
        try {
            return fetchFeed(data, dispatcherService.resolveActiveBaseUrl(), cookie, t);
        } catch (ApiException e) {
            if (e.getStatus().is4xxClientError() || e instanceof MirrorRequestLimit.Busy
                    || e instanceof MirrorRequestLimit.DeadlineExceeded) throw e;
            return retryOnFreshBase(data, cookie, t, e);
        } catch (RestClientResponseException e) {
            // The host answered — just not with content: e.g. nf's /mobile/home?app=1 is 404 on the
            // current mirror host while Prime's /pv/homepage.php serves fine from the same origin. A
            // 4xx is NOT a host rotation, so it must not invalidate the dispatcher cache or re-fetch:
            // doing so throws away the good base the other OTTs are serving from and fires a second
            // upstream call plus a full dispatcher re-walk on every home poll (a retry storm that
            // degrades the working catalogues). Surface it for rail() to log once and leave the rail
            // off; only a connection error or 5xx (below) is treated as a rotation worth re-resolving.
            if (e.getStatusCode().is4xxClientError() || MirrorCloudflare.isChallenge(e)) throw e;
            return retryOnFreshBase(data, cookie, t, e);
        } catch (RuntimeException e) {
            return retryOnFreshBase(data, cookie, t, e);
        }
    }

    private List<Title> retryOnFreshBase(CatalogueData data, String cookie, String t, RuntimeException first) {
        log.info("Mirror feed failed on the cached backend ({}); retrying on a freshly resolved host",
                first.getClass().getSimpleName());
        dispatcherService.invalidate();
        return fetchFeed(data, dispatcherService.resolveActiveBaseUrl(), cookie, t);
    }

    private List<Title> fetchFeed(CatalogueData data, String baseUrl, String cookie, String t) {
        String ott = MirrorOtt.of(cookie);
        // Netflix's categorised home (/mobile/home?app=1) was retired upstream — it answers a bare
        // router 404 now — so the Netflix (and default, catalogue-less) home is composed from the two
        // endpoints that stayed live: trending (search.php) and the post.php suggest graph. Hotstar (dp)
        // has no home or trending route of its own either (every /mobile/hs home candidate 404s), so its
        // home is likewise COMPOSED — from curated hs/search.php seeds + the hs/post.php suggest graph.
        // Only Prime keeps a real homepage (/pv/homepage.php, parsed from homeFeed below).
        if (isNetflixCatalogue(ott)) {
            return composeNetflixFeed(data, baseUrl, cookie, t);
        }
        if (MirrorOtt.HOTSTAR.equals(ott)) {
            return composeHotstarFeed(data, baseUrl, cookie, t);
        }
        String body = browseRepository.homeFeed(baseUrl, cookie);
        return MirrorHomeParser.parseHome(body, ott);
    }

    // The Netflix catalogue: its own "nf" token, or no token at all (the default home load names no
    // catalogue and upstream answers Netflix). Prime ("pv") is parsed from its own homepage; Hotstar
    // ("dp") is composed by composeHotstarFeed — neither is composed here.
    private static boolean isNetflixCatalogue(String ott) {
        return ott == null || ott.isBlank() || "nf".equals(ott);
    }

    /**
     * The Netflix home, composed from the two endpoints that outlived the retired {@code /mobile/home}.
     * {@code search.php}'s Top Searches is the one feed that arrives with title names, so it seeds
     * everything: the first ten become a <b>"Top 10 This Week"</b> rail, and each trending title's
     * {@code post.php} detail (fetched once, in parallel within {@link #COMPOSE_BUDGET_MILLIS}) yields
     * three things at once — its {@code genre} (bucketed into the <b>genre rails</b> below), its
     * {@code suggest} graph (the existing <b>"More Like &lt;title&gt;"</b> rails), and its name.
     *
     * <p>The genre rails bucket every pooled title whose genre we know — the trending titles plus any
     * suggested ids the the scoped cache already holds from earlier loads — into the Netflix
     * categories in {@link #GENRE_BUCKETS}, one rail per title's single best bucket, and show a rail
     * only once it clears {@link #MIN_GENRE_TILES}. So on a cold cache the rails draw from the ~20
     * trending titles alone and richen on every subsequent load as the name lookups fill the genre
     * cache — never padded, a sparse genre (Anime on an India-weighted day) simply stays hidden.
     *
     * <p>Rail order: Top 10, then the genre rails fullest-first, then the More Like rails. A title may
     * repeat between Top 10 and a genre rail (as it does on Netflix); the More Like rails are deduped
     * against everything already placed so they only surface fresh discovery tiles. Suggested tiles
     * whose names aren't cached yet carry ids only, for the build's own phase-1 lookup. An empty
     * trending list yields no rails rather than throwing.
     */
    private List<Title> composeNetflixFeed(CatalogueData data, String baseUrl, String cookie, String t) {
        JsonNode trending = mapper.readTree(browseRepository.trending(baseUrl, cookie));

        // The trending titles in order, each with its inline name (search.php is the only feed that
        // names them). Deduped, since the Top Searches list can repeat an id.
        List<Title> trend = new ArrayList<>();
        Set<String> trendIds = new LinkedHashSet<>();
        Map<String, String> trendName = new HashMap<>();
        for (JsonNode item : trending.path("searchResult")) {
            String id = item.path("id").asString("").trim();
            if (id.isEmpty() || !trendIds.add(id)) continue;
            String name = item.path("t").asString("").trim();
            if (!name.isEmpty()) trendName.put(id, name);
            trend.add(new Title(id, name.isEmpty() ? null : name, MirrorHomeParser.poster("nf", id), null));
        }
        if (trend.isEmpty()) return List.of();

        // One detail fetch per trending title (name + genre + suggest graph), in parallel on the shared
        // pool within the compose budget. Device headers copied now since a worker may outlive the
        // request. Each resolved detail caches its genre, so the genre rails grow across loads.
        ClientHeaders client = ClientHeaderSupport.current();
        List<Future<JsonNode>> futures = new ArrayList<>();
        for (Title tr : trend) {
            futures.add(submit(() -> ClientHeaderSupport.callAs(client, () -> {
                try {
                    return mapper.readTree(browseRepository.getDetail(baseUrl, tr.id(), t, cookie));
                } catch (RuntimeException e) {
                    log.debug("No detail for mirror title {}: {}", tr.id(), e.getClass().getSimpleName());
                    return null;
                }
            })));
        }
        long deadline = System.currentTimeMillis() + COMPOSE_BUDGET_MILLIS;
        List<JsonNode> details = new ArrayList<>();
        for (int i = 0; i < trend.size(); i++) {
            JsonNode detail = await(futures.get(i), deadline, trend.get(i).id());
            details.add(detail);
            if (detail != null) cacheGenre(data, trend.get(i).id(), detail);
        }

        // Widen the genre pool before the rails are built. The pool the genre rails bucket from is the
        // trending ids plus any suggested ids whose genre is ALREADY cached; on a cold cache that is the
        // ~20 trending titles alone — enough to fill only the commonest buckets, which is exactly why
        // Netflix showed just "Crime" and "Comedies". So here we fetch the genre of a capped slice of
        // the suggested ids we have not cached yet — the discovery graph around the trending titles,
        // where the Anime/Horror/Sci-Fi/Romance tiles actually live — within one compose budget,
        // caching each title's genre AND name (the one detail carries both, so the genre tiles then
        // render without a second lookup). Whatever resolves in budget widens THIS build's pool; the
        // rest stay uncached and fill on later loads. Deduped against the cache, so a warm catalogue
        // skips this almost entirely and pays nothing.
        LinkedHashSet<String> toWarm = new LinkedHashSet<>();
        for (JsonNode detail : details) {
            if (detail == null) continue;
            for (JsonNode s : detail.path("suggest")) {
                if (toWarm.size() >= GENRE_POOL_FETCH_MAX) break;
                String id = s.path("id").asString("").trim();
                if (!id.isEmpty() && !trendIds.contains(id) && !data.genreCache.containsKey(id)) {
                    toWarm.add(id);
                }
            }
        }
        if (!toWarm.isEmpty()) {
            List<String> warmIds = new ArrayList<>(toWarm);
            List<Future<JsonNode>> genreFutures = new ArrayList<>();
            for (String id : warmIds) {
                genreFutures.add(submit(() -> ClientHeaderSupport.callAs(client, () -> {
                    try {
                        return mapper.readTree(browseRepository.getDetail(baseUrl, id, t, cookie));
                    } catch (RuntimeException e) {
                        log.debug("No detail for mirror suggestion {}: {}", id, e.getClass().getSimpleName());
                        return null;
                    }
                })));
            }
            long genreDeadline = System.currentTimeMillis() + COMPOSE_BUDGET_MILLIS;
            for (int i = 0; i < warmIds.size(); i++) {
                JsonNode detail = await(genreFutures.get(i), genreDeadline, warmIds.get(i));
                if (detail == null) continue;
                cacheGenre(data, warmIds.get(i), detail);
                String name = detail.path("title").asString("").trim();
                if (!name.isEmpty() && data.nameCache.size() < NAME_CACHE_MAX) {
                    data.nameCache.putIfAbsent(warmIds.get(i), name);
                }
            }
        }

        // Seed the categories the suggest graph cannot reach (Anime, Cartoons). The franchise searches
        // run once per process (seedsSearched); each names its hits inline, so seedPool fills with ids and
        // their names in one pass. Then, exactly like the suggest warm-up above, resolve the genre of a
        // bounded slice of still-uncached seeded ids this load — the rest fill on the next one. Once an
        // id's genre is cached it is admitted to the pool below and buckets into Anime/Cartoons normally.
        if (data.seedsSearched.compareAndSet(false, true)) {
            try {
                List<Future<JsonNode>> searchFutures = new ArrayList<>();
                for (String q : SEED_QUERIES) {
                    searchFutures.add(submit(() -> ClientHeaderSupport.callAs(client, () -> {
                        try {
                            return mapper.readTree(browseRepository.search(baseUrl, q, t, cookie));
                        } catch (RuntimeException e) {
                            log.debug("Mirror seed search '{}' failed: {}", q, e.getClass().getSimpleName());
                            return null;
                        }
                    })));
                }
                long searchDeadline = System.currentTimeMillis() + COMPOSE_BUDGET_MILLIS;
                for (int i = 0; i < SEED_QUERIES.size(); i++) {
                    JsonNode res = await(searchFutures.get(i), searchDeadline, "seed:" + SEED_QUERIES.get(i));
                    if (res == null) continue;
                    int taken = 0;
                    for (JsonNode item : res.path("searchResult")) {
                        if (data.seedPool.size() >= SEED_POOL_MAX || taken >= SEED_HITS_PER_QUERY) break;
                        String id = item.path("id").asString("").trim();
                        if (id.isEmpty()) continue;
                        String name = item.path("t").asString("").trim();
                        if (!name.isEmpty() && data.nameCache.size() < NAME_CACHE_MAX) data.nameCache.putIfAbsent(id, name);
                        if (data.seedPool.add(id)) taken++;
                    }
                }
            } catch (RuntimeException e) {
                log.debug("Mirror seed discovery failed: {}", e.getClass().getSimpleName());
                data.seedsSearched.set(false); // let a later load retry the discovery
            }
        }
        List<String> seedToWarm = new ArrayList<>();
        for (String id : data.seedPool) {
            if (seedToWarm.size() >= SEED_POOL_MAX) break;
            if (!data.genreCache.containsKey(id)) seedToWarm.add(id);
        }
        if (!seedToWarm.isEmpty()) {
            List<Future<JsonNode>> seedGenreFutures = new ArrayList<>();
            for (String id : seedToWarm) {
                seedGenreFutures.add(submit(() -> ClientHeaderSupport.callAs(client, () -> {
                    try {
                        return mapper.readTree(browseRepository.getDetail(baseUrl, id, t, cookie));
                    } catch (RuntimeException e) {
                        log.debug("No detail for mirror seed {}: {}", id, e.getClass().getSimpleName());
                        return null;
                    }
                })));
            }
            // Seeds resolve once per process and then persist in genreCache, so the cold seed load is
            // allowed a wider budget than a normal compose — it is the one-time price of a full Anime and
            // Cartoons rail on the first real load, not a per-request cost. Whatever misses still tops up
            // on the next load, since an uncached seed id is retried here each time.
            long seedDeadline = System.currentTimeMillis() + 3 * COMPOSE_BUDGET_MILLIS;
            for (int i = 0; i < seedToWarm.size(); i++) {
                JsonNode detail = await(seedGenreFutures.get(i), seedDeadline, seedToWarm.get(i));
                if (detail == null) continue;
                cacheGenre(data, seedToWarm.get(i), detail);
                String name = detail.path("title").asString("").trim();
                if (!name.isEmpty() && data.nameCache.size() < NAME_CACHE_MAX) {
                    data.nameCache.putIfAbsent(seedToWarm.get(i), name);
                }
            }
        }

        List<Title> out = new ArrayList<>();

        // [1] Top 10 This Week — the mirror's own Top Searches order. Named inline, so the build's
        // phase-1 skips a lookup for these.
        int top = Math.min(10, trend.size());
        for (int i = 0; i < top; i++) {
            Title tr = trend.get(i);
            out.add(new Title(tr.id(), tr.title(), tr.image(), "Top 10 This Week"));
        }

        // [2] Genre rails. The pool is the trending ids plus any suggested ids whose genre the cache
        // already holds (so rails richen over loads without extra request-path fetches). Each pooled
        // title joins its single best bucket once; buckets that clear MIN_GENRE_TILES are emitted
        // fullest-first. A tile may also appear in Top 10 above — Netflix repeats a title across rows.
        LinkedHashSet<String> pool = new LinkedHashSet<>(trendIds);
        for (JsonNode detail : details) {
            if (detail == null) continue;
            for (JsonNode s : detail.path("suggest")) {
                String id = s.path("id").asString("").trim();
                if (!id.isEmpty() && data.genreCache.containsKey(id)) pool.add(id);
            }
        }
        // The seeded anime/cartoon catalogue, admitted once its genre is cached (names are already in
        // nameCache from the inline search hits, so the bucketing below renders them without a lookup).
        for (String id : data.seedPool) {
            if (data.genreCache.containsKey(id)) pool.add(id);
        }
        LinkedHashMap<String, List<Title>> byBucket = new LinkedHashMap<>();
        Set<String> inGenre = new HashSet<>();
        for (String id : pool) {
            String bucket = bestBucket(data.genreCache.get(id));
            if (bucket == null || !inGenre.add(id)) continue;
            String name = trendName.getOrDefault(id, data.nameCache.get(id)); // null -> build phase-1 names it
            byBucket.computeIfAbsent(bucket, k -> new ArrayList<>())
                    .add(new Title(id, name, MirrorHomeParser.poster("nf", id), bucket));
        }
        byBucket.values().stream()
                .filter(rail -> rail.size() >= MIN_GENRE_TILES)
                .sorted((a, b) -> Integer.compare(b.size(), a.size()))
                .forEach(out::addAll);

        // [3] More Like <title> — discovery from the suggest graph of the first few named seeds, for
        // tiles not already placed in Top 10 or a genre rail, so these rails only add fresh titles.
        Set<String> placed = new HashSet<>(trendIds);
        placed.addAll(inGenre);
        int seeds = 0;
        for (int i = 0; i < trend.size() && seeds < RELATED_SEEDS; i++) {
            JsonNode detail = details.get(i);
            Title tr = trend.get(i);
            if (detail == null || tr.title() == null) continue;
            String category = "More Like " + tr.title();
            List<Title> rail = new ArrayList<>();
            for (JsonNode s : detail.path("suggest")) {
                String id = s.path("id").asString("").trim();
                if (id.isEmpty() || !placed.add(id)) continue;
                rail.add(new Title(id, data.nameCache.get(id), MirrorHomeParser.poster("nf", id), category));
            }
            if (!rail.isEmpty()) {
                out.addAll(rail);
                seeds++;
            }
        }
        return out;
    }

    /**
     * The Hotstar ({@code dp}) home, composed the same way as the Netflix home but without a trending
     * feed to seed from — upstream exposes no Hotstar home or trending route (every {@code /mobile/hs}
     * home candidate 404s, verified live 2026-11-23), so the catalogue is entered through a curated set
     * of {@code hs/search.php} title searches ({@link #HS_SEED_QUERIES}), run once per process and
     * persisted in the catalogue metadata cache. Each search names its hits inline; each seed title's
     * {@code hs/post.php} detail then yields its {@code genre} (bucketed into the shared
     * {@link #GENRE_BUCKETS} rails) and its {@code suggest} graph (the "More Like &lt;title&gt;" rails),
     * exactly as {@link #composeNetflixFeed} does.
     *
     * <p>Rail order: "Popular on Hotstar" (the lead seeds, named inline), then the genre rails
     * fullest-first (shown only once they clear {@link #MIN_GENRE_TILES}), then the More Like rails
     * (deduped against everything already placed). On a cold cache the genre rails draw from the seed
     * details alone and richen over loads as the suggest/leftover-seed genres fill the cache — never
     * padded. An empty seed pool (every search missed) yields no rails rather than throwing.
     */
    private List<Title> composeHotstarFeed(CatalogueData data, String baseUrl, String cookie, String t) {
        ClientHeaders client = ClientHeaderSupport.current();

        // One-time seed discovery: run the curated Hotstar searches in parallel, naming hits inline.
        // This is the Hotstar catalogue's only entry point, so unlike the Netflix anime/cartoon seed it
        // is not optional — a failure clears the gate so a later load retries it.
        if (data.hsSeedsSearched.compareAndSet(false, true)) {
            try {
                List<Future<JsonNode>> searchFutures = new ArrayList<>();
                for (String q : HS_SEED_QUERIES) {
                    searchFutures.add(submit(() -> ClientHeaderSupport.callAs(client, () -> {
                        try {
                            return mapper.readTree(browseRepository.search(baseUrl, q, t, cookie));
                        } catch (RuntimeException e) {
                            log.debug("Hotstar seed search '{}' failed: {}", q, e.getClass().getSimpleName());
                            return null;
                        }
                    })));
                }
                // Seeds resolve once per process and then persist, so the cold seed load is allowed a
                // wider budget than a normal compose — the one-time price of a full Hotstar home.
                long searchDeadline = System.currentTimeMillis() + 3 * COMPOSE_BUDGET_MILLIS;
                for (int i = 0; i < HS_SEED_QUERIES.size(); i++) {
                    JsonNode res = await(searchFutures.get(i), searchDeadline, "hs-seed:" + HS_SEED_QUERIES.get(i));
                    if (res == null) continue;
                    int taken = 0;
                    for (JsonNode item : res.path("searchResult")) {
                        if (data.hsSeedPool.size() >= SEED_POOL_MAX || taken >= SEED_HITS_PER_QUERY) break;
                        String id = item.path("id").asString("").trim();
                        if (id.isEmpty()) continue;
                        String name = item.path("t").asString("").trim();
                        if (!name.isEmpty() && data.nameCache.size() < NAME_CACHE_MAX) data.nameCache.putIfAbsent(id, name);
                        if (data.hsSeedPool.add(id)) taken++;
                    }
                }
            } catch (RuntimeException e) {
                log.debug("Hotstar seed discovery failed: {}", e.getClass().getSimpleName());
                data.hsSeedsSearched.set(false); // let a later load retry the discovery
            }
        }

        // Snapshot the seed pool in discovery order, each with its inline name.
        List<Title> seeds = new ArrayList<>();
        LinkedHashSet<String> seedIds = new LinkedHashSet<>();
        synchronized (data.hsSeedPool) {
            for (String id : data.hsSeedPool) {
                if (!seedIds.add(id)) continue;
                seeds.add(new Title(id, data.nameCache.get(id), MirrorHomeParser.poster("dp", id), null));
            }
        }
        if (seeds.isEmpty()) return List.of();

        // One detail fetch per lead seed (name + genre + suggest graph), in parallel within the budget.
        int primary = Math.min(HS_PRIMARY_SEEDS, seeds.size());
        List<Title> head = new ArrayList<>(seeds.subList(0, primary));
        List<Future<JsonNode>> futures = new ArrayList<>();
        for (Title s : head) {
            futures.add(submit(() -> ClientHeaderSupport.callAs(client, () -> {
                try {
                    return mapper.readTree(browseRepository.getDetail(baseUrl, s.id(), t, cookie));
                } catch (RuntimeException e) {
                    log.debug("No detail for Hotstar title {}: {}", s.id(), e.getClass().getSimpleName());
                    return null;
                }
            })));
        }
        long deadline = System.currentTimeMillis() + COMPOSE_BUDGET_MILLIS;
        List<JsonNode> details = new ArrayList<>();
        for (int i = 0; i < head.size(); i++) {
            JsonNode detail = await(futures.get(i), deadline, head.get(i).id());
            details.add(detail);
            if (detail != null) cacheGenre(data, head.get(i).id(), detail);
        }

        // Widen the genre pool: warm the genre (and name) of uncached suggested ids, plus any seed ids
        // beyond the lead slice whose genre is not cached yet, within one budget — so the genre rails
        // surface on the first load and the whole seed pool eventually buckets. Deduped against the
        // cache, so a warm catalogue skips this almost entirely.
        LinkedHashSet<String> toWarm = new LinkedHashSet<>();
        for (JsonNode detail : details) {
            if (detail == null) continue;
            for (JsonNode s : detail.path("suggest")) {
                if (toWarm.size() >= GENRE_POOL_FETCH_MAX) break;
                String id = s.path("id").asString("").trim();
                if (!id.isEmpty() && !seedIds.contains(id) && !data.genreCache.containsKey(id)) toWarm.add(id);
            }
        }
        for (int i = primary; i < seeds.size() && toWarm.size() < GENRE_POOL_FETCH_MAX; i++) {
            String id = seeds.get(i).id();
            if (!data.genreCache.containsKey(id)) toWarm.add(id);
        }
        if (!toWarm.isEmpty()) {
            List<String> warmIds = new ArrayList<>(toWarm);
            List<Future<JsonNode>> genreFutures = new ArrayList<>();
            for (String id : warmIds) {
                genreFutures.add(submit(() -> ClientHeaderSupport.callAs(client, () -> {
                    try {
                        return mapper.readTree(browseRepository.getDetail(baseUrl, id, t, cookie));
                    } catch (RuntimeException e) {
                        log.debug("No detail for Hotstar suggestion {}: {}", id, e.getClass().getSimpleName());
                        return null;
                    }
                })));
            }
            long genreDeadline = System.currentTimeMillis() + COMPOSE_BUDGET_MILLIS;
            for (int i = 0; i < warmIds.size(); i++) {
                JsonNode detail = await(genreFutures.get(i), genreDeadline, warmIds.get(i));
                if (detail == null) continue;
                cacheGenre(data, warmIds.get(i), detail);
                String name = detail.path("title").asString("").trim();
                if (!name.isEmpty() && data.nameCache.size() < NAME_CACHE_MAX) {
                    data.nameCache.putIfAbsent(warmIds.get(i), name);
                }
            }
        }

        List<Title> out = new ArrayList<>();

        // [1] Popular on Hotstar — the curated seed order, named inline, so the build's phase-1 skips a
        // lookup for these.
        int top = Math.min(10, seeds.size());
        for (int i = 0; i < top; i++) {
            Title s = seeds.get(i);
            out.add(new Title(s.id(), s.title(), s.image(), "Popular on Hotstar"));
        }

        // [2] Genre rails. The pool is the seed ids plus any suggested ids whose genre the cache already
        // holds; each pooled title joins its single best bucket once, and buckets that clear
        // MIN_GENRE_TILES are emitted fullest-first.
        LinkedHashSet<String> genrePool = new LinkedHashSet<>(seedIds);
        for (JsonNode detail : details) {
            if (detail == null) continue;
            for (JsonNode s : detail.path("suggest")) {
                String id = s.path("id").asString("").trim();
                if (!id.isEmpty() && data.genreCache.containsKey(id)) genrePool.add(id);
            }
        }
        LinkedHashMap<String, List<Title>> byBucket = new LinkedHashMap<>();
        Set<String> inGenre = new HashSet<>();
        for (String id : genrePool) {
            String bucket = bestBucket(data.genreCache.get(id));
            if (bucket == null || !inGenre.add(id)) continue;
            String name = data.nameCache.get(id); // null -> build phase-1 names it
            byBucket.computeIfAbsent(bucket, k -> new ArrayList<>())
                    .add(new Title(id, name, MirrorHomeParser.poster("dp", id), bucket));
        }
        byBucket.values().stream()
                .filter(rail -> rail.size() >= MIN_GENRE_TILES)
                .sorted((a, b) -> Integer.compare(b.size(), a.size()))
                .forEach(out::addAll);

        // [3] More Like <title> — discovery from the suggest graph of the first few named seeds, for
        // tiles not already placed, so these rails only add fresh titles.
        Set<String> placed = new HashSet<>(seedIds);
        placed.addAll(inGenre);
        int used = 0;
        for (int i = 0; i < head.size() && used < RELATED_SEEDS; i++) {
            JsonNode detail = details.get(i);
            Title s = head.get(i);
            if (detail == null || s.title() == null) continue;
            String category = "More Like " + s.title();
            List<Title> rail = new ArrayList<>();
            for (JsonNode sg : detail.path("suggest")) {
                String id = sg.path("id").asString("").trim();
                if (id.isEmpty() || !placed.add(id)) continue;
                rail.add(new Title(id, data.nameCache.get(id), MirrorHomeParser.poster("dp", id), category));
            }
            if (!rail.isEmpty()) {
                out.addAll(rail);
                used++;
            }
        }
        return out;
    }

    private static <T> T await(Future<T> future, long deadline, String id) {
        try {
            return future.get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        } catch (Exception e) {
            future.cancel(true);
            log.debug("No playlist for mirror title {}: {}", id, e.getClass().getSimpleName());
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        refreshWorker.shutdownNow();
        pool.shutdownNow();
    }
}
