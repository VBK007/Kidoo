package com.example.kido.mymirror.catalogue;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.springframework.data.domain.PageRequest;
import com.example.kido.mymirror.MirrorStreamSigner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.kido.media.dto.HomeDtos.MirrorRailDto;
import com.example.kido.media.dto.HomeDtos.MirrorTileDto;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The catalogue cache's one gateway to the database. Everything here is gated by
 * {@link MirrorCatalogueProperties}{@code .persistence.enabled} (default false): with persistence
 * off, {@link #publish} and {@link #read} are no-ops, so an unconfigured deployment never reads or
 * writes a catalogue row.
 *
 * <p>Write safety is the whole point of this class:
 * <ul>
 *   <li><b>Stale-write prevention, by atomic compare-and-set.</b> Each build takes a
 *       process-monotonic {@code buildSeq} from {@link #nextSequence()} <i>when it starts</i>. The
 *       snapshot write is a single guarded {@code UPDATE ... WHERE :seq &gt; build_seq AND
 *       (:complete OR NOT complete)} (falling back to an {@code INSERT ... ON CONFLICT DO NOTHING}
 *       for the cold row). A publish that is not strictly newer updates zero rows and is dropped —
 *       so a slow build that began earlier can never clobber a newer catalogue that finished first,
 *       and two racing publishers resolve deterministically in the database with no exception and no
 *       rolled-back transaction. The guard, not row-version optimistic locking, is the arbiter.</li>
 *   <li><b>Complete beats partial.</b> The same {@code (:complete OR NOT complete)} clause means a
 *       {@code partial} build can only land on a row that is itself not complete; a complete
 *       snapshot is never overwritten by a partial one, and the attempt is still recorded.</li>
 *   <li><b>Concurrency-safe upserts, no swallowed rollbacks.</b> Items and relations are updated in
 *       place by their unique key, then inserted with {@code ON CONFLICT DO NOTHING} when that
 *       touched no row — the database constraint is the arbiter, and no unique-violation exception is
 *       ever thrown to poison the transaction. The metadata refresh runs in a <i>separate</i>
 *       committed transaction from the snapshot write, so a hiccup there can never roll back the
 *       already-committed last-known-good snapshot.</li>
 *   <li><b>No secrets persisted; posters are allowlisted.</b> Tile playlists (which can carry signed
 *       stream URLs) are stripped before serialization; a poster is stored only if it is a plain
 *       {@code https} URL on an allowed CDN host with no userinfo, no query string and a {@code .jpg}
 *       path — anything else becomes a null placeholder rather than a guessed-at reference.</li>
 * </ul>
 */
@Service
public class MirrorCatalogueStore {

    private static final Logger log = LoggerFactory.getLogger(MirrorCatalogueStore.class);

    /** The kind tag for "More Like <title>" recommendation edges. */
    private static final String KIND_MORE_LIKE = "more-like";
    private static final String MORE_LIKE_PREFIX = "more like";

    private final MirrorCatalogueItemRepository items;
    private final MirrorCatalogueRelationRepository relations;
    private final MirrorCatalogueSnapshotRepository snapshots;
    private final MirrorCatalogueProperties props;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    /**
     * The snapshot CAS and the best-effort metadata refresh run in <i>independent</i> committed
     * transactions ({@code REQUIRES_NEW}), so a failure folding items/relations in can never roll
     * back the already-committed last-known-good snapshot. Built from the shared transaction manager
     * rather than relying on {@code @Transactional} self-invocation (which a {@code this.}-call would
     * bypass anyway).
     */
    private final TransactionTemplate snapshotTx;
    private final TransactionTemplate metadataTx;
    private final TransactionTemplate cleanupTx;
    private static final int CLEANUP_BATCH = 16;

    /** The poster host allowlist from configuration, lowercased once for case-insensitive matching. */
    private final Set<String> allowedPosterHosts;

    private final AtomicLong sequence = new AtomicLong(0);
    private volatile boolean sequenceSeeded = false;

    public MirrorCatalogueStore(MirrorCatalogueItemRepository items,
                                MirrorCatalogueRelationRepository relations,
                                MirrorCatalogueSnapshotRepository snapshots,
                                MirrorCatalogueProperties props,
                                PlatformTransactionManager txManager) {
        this.items = items;
        this.relations = relations;
        this.snapshots = snapshots;
        this.props = props;
        this.snapshotTx = new TransactionTemplate(txManager);
        this.snapshotTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.metadataTx = new TransactionTemplate(txManager);
        this.metadataTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.cleanupTx = new TransactionTemplate(txManager);
        this.cleanupTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.cleanupTx.setTimeout(3);
        this.allowedPosterHosts = normalizeHosts(props.getAllowedPosterHosts());
    }

    private static Set<String> normalizeHosts(List<String> hosts) {
        Set<String> out = new LinkedHashSet<>();
        if (hosts != null) {
            for (String h : hosts) {
                if (h != null && !h.isBlank()) {
                    out.add(h.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(out);
    }

    // ------------------------------------------------------------------ public API

    public boolean persistenceEnabled() {
        return props.getPersistence().isEnabled();
    }

    public MirrorCatalogueProperties.BackgroundRefresh backgroundRefreshPolicy() {
        return props.getBackgroundRefresh();
    }

    /** The configured, non-secret catalogue scope. Never a cookie, token or profile id. */
    public String scope() {
        return props.getScope();
    }

    /** Versioned physical key for an authorized generation within the configured deployment scope. */
    public String namespace(String epoch, MirrorStreamSigner signer) {
        return namespacePrefix() + signer.sign("catalogue-generation\n" + scope() + "\n" + epoch);
    }

    private String namespacePrefix() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(scope().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "g2-" + java.util.HexFormat.of().formatHex(digest, 0, 8) + "-";
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Delete an age-limited batch belonging to this deployment, leaving the active generation intact. */
    public int retireInactiveNamespaces(String active, BooleanSupplier stillCurrent) {
        if (!persistenceEnabled() || active == null || !stillCurrent.getAsBoolean()) return 0;
        String prefix = namespacePrefix();
        if (!active.startsWith(prefix)) throw new IllegalArgumentException("Invalid active catalogue namespace");
        String pattern = prefix + "%";
        Instant cutoff = Instant.now().minus(props.getFreshness().getHardExpiry());
        return cleanupTx.execute(status -> {
            int deleted = 0;
            for (var candidate : snapshots.expiredNamespaces(pattern, active, cutoff, PageRequest.of(0, CLEANUP_BATCH))) {
                if (!stillCurrent.getAsBoolean()) {
                    status.setRollbackOnly();
                    return 0;
                }
                if (snapshots.deleteExpired(candidate.getId(), cutoff) == 1) {
                    relations.deleteNamespace(candidate.getScope(), candidate.getOtt());
                    items.deleteNamespace(candidate.getScope(), candidate.getOtt());
                    deleted++;
                }
            }
            if (!stillCurrent.getAsBoolean()) {
                status.setRollbackOnly();
                return 0;
            }
            return deleted;
        });
    }

    /** Normalize an OTT token to the stored form; blank/unknown defaults to Netflix ("nf"). */
    public String ott(String raw) {
        if (raw == null || raw.isBlank()) {
            return "nf";
        }
        return raw.trim().toLowerCase();
    }

    /**
     * A build's monotonic sequence token, taken once when the build starts. Seeded lazily from the
     * max persisted {@code buildSeq} so it stays monotonic across a restart. Returns 0 (a value no
     * publish accepts over an existing row) when persistence is off.
     */
    public long nextSequence() {
        if (!persistenceEnabled()) {
            return 0L;
        }
        if (!sequenceSeeded) {
            seedSequence();
        }
        return sequence.incrementAndGet();
    }

    private synchronized void seedSequence() {
        if (sequenceSeeded) {
            return;
        }
        try {
            sequence.set(snapshots.maxBuildSeq());
        } catch (RuntimeException e) {
            log.warn("catalogue: could not seed build sequence, starting at 0", e);
            sequence.set(0);
        }
        sequenceSeeded = true;
    }

    /** What a warm read hands back: the rails, their freshness, and when they were last rebuilt. */
    public record CatalogueView(List<MirrorRailDto> rails, Freshness freshness, Instant lastSuccessfulRefresh) {}

    public enum Freshness { FRESH, STALE, PARTIAL }

    /**
     * Read the last-known-good catalogue for a scoped OTT. Never touches upstream and never throws:
     * any miss, deserialization failure or hard-expired snapshot yields {@link Optional#empty()}.
     * A served snapshot's {@code last_access} is bumped with a version-free bulk update, so this
     * stays off the optimistic-lock path.
     */
    @Transactional
    public Optional<CatalogueView> read(String ott) {
        return readInNamespace(scope(), ott);
    }

    @Transactional
    public Optional<CatalogueView> readInNamespace(String namespace, String ott) {
        if (!persistenceEnabled()) {
            return Optional.empty();
        }
        String scope = namespace;
        String ottKey = ott(ott);
        Optional<MirrorCatalogueSnapshot> found;
        try {
            found = snapshots.findByScopeAndOtt(scope, ottKey);
        } catch (RuntimeException e) {
            log.warn("catalogue: snapshot read failed for {}/{}", scope, ottKey, e);
            return Optional.empty();
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MirrorCatalogueSnapshot s = found.get();
        Instant now = Instant.now();
        Duration age = Duration.between(s.getLastSuccessfulRefresh(), now);
        if (age.compareTo(props.getFreshness().getHardExpiry()) > 0) {
            // Past permitted retention: do not serve, even as a fallback.
            return Optional.empty();
        }
        List<MirrorRailDto> rails = deserialize(s.getRailsJson());
        if (rails == null || rails.isEmpty()) {
            return Optional.empty();
        }
        Freshness freshness = !s.isComplete() ? Freshness.PARTIAL
                : age.compareTo(props.getFreshness().getSoftTtl()) > 0 ? Freshness.STALE
                : Freshness.FRESH;
        try {
            snapshots.markAccessed(scope, ottKey, now);
        } catch (RuntimeException e) {
            log.debug("catalogue: last_access bump failed for {}/{}", scope, ottKey, e);
        }
        return Optional.of(new CatalogueView(rails, freshness, s.getLastSuccessfulRefresh()));
    }

    /**
     * Publish a freshly composed catalogue as the new last-known-good snapshot and refresh the
     * item/relation tables. The snapshot write and the metadata refresh are two <i>separate</i>
     * committed transactions: the snapshot is the source of truth for a warm read, so a hiccup in the
     * best-effort metadata refresh can never roll it back. Returns true only if this build actually
     * became the stored snapshot. A no-op (returns false) when persistence is off, when {@code rails}
     * is empty, when {@code buildSeq} is not newer than the stored one, or when a complete snapshot
     * would be overwritten by a partial one.
     *
     * @param buildSeq the token from {@link #nextSequence()} taken when this build started
     */
    public boolean publish(String ott, List<MirrorRailDto> rails, boolean complete, long buildSeq) {
        return publishInNamespace(scope(), ott, rails, complete, buildSeq);
    }

    public boolean publishInNamespace(String namespace, String ott, List<MirrorRailDto> rails,
                                      boolean complete, long buildSeq) {
        return publishInNamespace(namespace, ott, rails, complete, buildSeq, () -> true);
    }

    public boolean publishInNamespace(String namespace, String ott, List<MirrorRailDto> rails,
                                      boolean complete, long buildSeq, BooleanSupplier stillCurrent) {
        if (!persistenceEnabled() || rails == null || rails.isEmpty() || !stillCurrent.getAsBoolean()) {
            return false;
        }
        String scope = namespace;
        String ottKey = ott(ott);
        Instant now = Instant.now();

        List<MirrorRailDto> sanitized = sanitizeRails(rails);
        String json = serialize(sanitized);
        if (json == null) {
            return false;
        }
        int itemCount = countTiles(sanitized);

        // Tx1 — the atomic snapshot compare-and-set, committed on its own. No exception path: a
        // losing publish updates zero rows and returns false.
        Boolean stored = snapshotTx.execute(status -> {
            boolean changed = writeSnapshot(scope, ottKey, json, sanitized.size(), itemCount, complete, buildSeq, now);
            // Recheck after the write acquired its database row lock. A delayed, superseded
            // publisher cannot recreate a namespace that the cleanup transaction just removed.
            if (!stillCurrent.getAsBoolean()) {
                status.setRollbackOnly();
                return false;
            }
            return changed;
        });
        if (!Boolean.TRUE.equals(stored)) {
            return false;
        }

        // Tx2 — best-effort item/relation refresh, in a transaction of its own. Its failure is logged
        // and swallowed; because it is a separate transaction, it cannot undo the committed snapshot.
        try {
            metadataTx.executeWithoutResult(status -> {
                if (scope.startsWith("g2-")) {
                    var snapshot = snapshots.lockSnapshot(scope, ottKey);
                    if (snapshot.isEmpty() || snapshot.get().getBuildSeq() != buildSeq
                            || !stillCurrent.getAsBoolean()) return;
                }
                upsertItems(scope, ottKey, sanitized, now);
                upsertRelations(scope, ottKey, sanitized, now);
                enforceRetention(scope, ottKey, now);
                if (!stillCurrent.getAsBoolean()) status.setRollbackOnly();
            });
        } catch (RuntimeException e) {
            log.warn("catalogue: item/relation refresh failed for {}/{} (snapshot kept)", scope, ottKey, e);
        }
        return true;
    }

    // ------------------------------------------------------------------ snapshot write

    /**
     * Store this build as the snapshot iff it is strictly newer (and not a partial over a complete),
     * using two portable, exception-free primitives so no concurrency path can poison the
     * transaction:
     * <ol>
     *   <li>a guarded {@code UPDATE} ({@link MirrorCatalogueSnapshotRepository#conditionalReplace})
     *       whose {@code WHERE} is the whole write policy — it updates the one row only when
     *       {@code :seq &gt; build_seq} and the complete/partial rule holds;</li>
     *   <li>an {@code INSERT ... ON CONFLICT DO NOTHING}
     *       ({@link MirrorCatalogueSnapshotRepository#insertIfAbsent}) for the cold row, which a
     *       concurrent insert simply no-ops instead of throwing;</li>
     *   <li>one more guarded {@code UPDATE}, in case a racing publisher inserted the row between (1)
     *       and (2) — now this build competes with theirs under the same seq guard.</li>
     * </ol>
     * Zero rows across all three means this build was legitimately superseded (older/equal seq, or a
     * partial that may not overwrite a complete snapshot); the attempt is recorded and false returned.
     */
    private boolean writeSnapshot(String scope, String ott, String json, int railCount, int itemCount,
                                  boolean complete, long buildSeq, Instant now) {
        if (snapshots.conditionalReplace(scope, ott, json, railCount, itemCount, complete, buildSeq, now) > 0) {
            return true;
        }
        if (snapshots.insertIfAbsent(UUID.randomUUID().toString(), scope, ott, json,
                railCount, itemCount, complete, buildSeq, now) > 0) {
            return true;
        }
        if (snapshots.conditionalReplace(scope, ott, json, railCount, itemCount, complete, buildSeq, now) > 0) {
            return true;
        }
        // Legitimately superseded: a newer (or equal) snapshot stands, or ours is a partial build that
        // may not overwrite a complete one. Record that a refresh was attempted and drop this build.
        snapshots.markAttempted(scope, ott, now);
        return false;
    }

    // ------------------------------------------------------------------ items

    private void upsertItems(String scope, String ott, List<MirrorRailDto> rails, Instant now) {
        int max = Math.max(0, props.getMaxItems());
        Set<String> seen = new LinkedHashSet<>();
        for (MirrorRailDto rail : rails) {
            if (rail == null || rail.items() == null) {
                continue;
            }
            for (MirrorTileDto tile : rail.items()) {
                if (tile == null || isBlank(tile.id()) || isBlank(tile.title())) {
                    continue;
                }
                String mirrorId = tile.id();
                if (mirrorId.length() > MirrorCatalogueItem.MIRROR_ID_MAX || !seen.add(mirrorId)) {
                    continue;
                }
                if (seen.size() > max) {
                    return; // storage bound reached
                }
                upsertItem(scope, ott, tile, now);
            }
        }
    }

    private void upsertItem(String scope, String ott, MirrorTileDto tile, Instant now) {
        String poster = safePoster(tile.image());
        String name = clip(tile.title(), MirrorCatalogueItem.NAME_MAX);
        // Update in place by the unique key first; if no row was there, insert-if-absent; if a racing
        // insert beat us, fold our values in with a final touch. No unique-violation exception is ever
        // thrown (ON CONFLICT DO NOTHING), so a same-key race cannot roll the metadata transaction back.
        if (items.touch(scope, ott, tile.id(), name, poster, null, null, null, now) > 0) {
            return;
        }
        if (items.insertIfAbsent(UUID.randomUUID().toString(), scope, ott, tile.id(),
                name, poster, null, null, null, now) > 0) {
            return;
        }
        items.touch(scope, ott, tile.id(), name, poster, null, null, null, now);
    }

    // ------------------------------------------------------------------ relations

    private void upsertRelations(String scope, String ott, List<MirrorRailDto> rails, Instant now) {
        for (MirrorRailDto rail : rails) {
            if (!isRecommendation(rail) || isBlank(rail.key())) {
                continue;
            }
            String sourceRef = clip(rail.key(), MirrorCatalogueRelation.SOURCE_REF_MAX);
            relations.deleteForSource(scope, ott, sourceRef);
            int ordinal = 0;
            Set<String> seen = new LinkedHashSet<>();
            for (MirrorTileDto tile : rail.items()) {
                if (tile == null || isBlank(tile.id())
                        || tile.id().length() > MirrorCatalogueRelation.RELATED_ID_MAX
                        || !seen.add(tile.id())) {
                    continue;
                }
                // Insert-if-absent: ON CONFLICT DO NOTHING makes a re-published or concurrently-written
                // edge idempotent instead of throwing on the unique key, so rebuilding a rail never
                // poisons the transaction. The preceding deleteForSource has cleared the old edge set.
                relations.insertIfAbsent(UUID.randomUUID().toString(), scope, ott, sourceRef,
                        tile.id(), KIND_MORE_LIKE, ordinal++, now);
            }
        }
    }

    /** A "More Like <title>" rail is the mirror's own suggest graph; nothing else is a relation. */
    private boolean isRecommendation(MirrorRailDto rail) {
        if (rail == null || rail.items() == null || rail.items().isEmpty()) {
            return false;
        }
        String title = rail.title();
        return title != null && title.trim().toLowerCase().startsWith(MORE_LIKE_PREFIX);
    }

    // ------------------------------------------------------------------ retention

    private void enforceRetention(String scope, String ott, Instant now) {
        Instant cutoff = now.minus(props.getFreshness().getHardExpiry());
        items.deleteStale(scope, ott, cutoff);
    }

    // ------------------------------------------------------------------ sanitize + json

    /**
     * Strip every tile's playlist before persistence — it can carry signed, session-bound stream
     * URLs that must never be stored — and drop unsafe posters. The client re-fetches a playlist on
     * tap, so a tile with a null playlist stays fully functional.
     */
    private List<MirrorRailDto> sanitizeRails(List<MirrorRailDto> rails) {
        List<MirrorRailDto> out = new ArrayList<>(rails.size());
        for (MirrorRailDto rail : rails) {
            if (rail == null) {
                continue;
            }
            List<MirrorTileDto> tiles = new ArrayList<>();
            if (rail.items() != null) {
                for (MirrorTileDto tile : rail.items()) {
                    if (tile == null) {
                        continue;
                    }
                    tiles.add(new MirrorTileDto(tile.id(), tile.title(), safePoster(tile.image()), null));
                }
            }
            out.add(new MirrorRailDto(rail.key(), rail.title(), tiles));
        }
        return out;
    }

    private int countTiles(List<MirrorRailDto> rails) {
        int n = 0;
        for (MirrorRailDto rail : rails) {
            if (rail != null && rail.items() != null) {
                n += rail.items().size();
            }
        }
        return n;
    }

    private String serialize(List<MirrorRailDto> rails) {
        try {
            return mapper.writeValueAsString(new SnapshotEnvelope(rails));
        } catch (RuntimeException e) {
            log.warn("catalogue: snapshot serialization failed", e);
            return null;
        }
    }

    private List<MirrorRailDto> deserialize(String json) {
        if (isBlank(json)) {
            return List.of();
        }
        try {
            SnapshotEnvelope env = mapper.readValue(json, SnapshotEnvelope.class);
            return env.rails() == null ? List.of() : env.rails();
        } catch (RuntimeException e) {
            log.warn("catalogue: snapshot deserialization failed", e);
            return List.of();
        }
    }

    /** Envelope so the codebase's {@code readValue(json, Class)} round-trips a typed rail list. */
    private record SnapshotEnvelope(List<MirrorRailDto> rails) {}

    // ------------------------------------------------------------------ helpers

    /**
     * Keep only a poster reference verified safe and stable to persist — an allowlist, not a blocklist.
     * The parser's legitimate poster is a plain {@code https://<cdn>/<ott>/.../<id>.jpg} with no query
     * and no credentials. A reference is stored only when <em>all</em> hold; otherwise it is dropped to
     * null and the client placeholder covers it (stripping a query string is <em>not</em> assumed to
     * turn a signed URL into a stable one):
     * <ul>
     *   <li>parses as an absolute URI;</li>
     *   <li>scheme is {@code https} (a plain-{@code http} or scheme-relative ref is not persisted);</li>
     *   <li>no userinfo in the authority (no {@code user:pass@host} credentials);</li>
     *   <li>no query string (session tokens / signatures live there) and no fragment;</li>
     *   <li>host is on the configured allowlist ({@link MirrorCatalogueProperties#getAllowedPosterHosts});</li>
     *   <li>path ends in {@code .jpg};</li>
     *   <li>within the stored-length bound.</li>
     * </ul>
     */
    private String safePoster(String image) {
        if (isBlank(image)) {
            return null;
        }
        String trimmed = image.trim();
        if (trimmed.length() > MirrorCatalogueItem.POSTER_MAX) {
            return null;
        }
        final URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException malformed) {
            return null;
        }
        if (!uri.isAbsolute() || uri.isOpaque()) {
            return null; // must be a hierarchical absolute URL, not a bare path or mailto:-style ref
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return null; // signed/plain-http references are never persisted
        }
        if (uri.getRawUserInfo() != null) {
            return null; // no credentials embedded in the authority
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return null; // session tokens / signatures ride the query or fragment
        }
        String host = uri.getHost();
        if (host == null || !allowedPosterHosts.contains(host.toLowerCase(Locale.ROOT))) {
            return null; // unknown host: not an established, permitted poster source
        }
        String path = uri.getPath();
        if (path == null || !path.toLowerCase(Locale.ROOT).endsWith(".jpg")) {
            return null; // only the CDN's stable .jpg poster shape is stored
        }
        return trimmed;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
