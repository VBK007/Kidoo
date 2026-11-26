package com.example.kido.mymirror.catalogue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 *   <li><b>Stale-write prevention.</b> Each build takes a process-monotonic {@code buildSeq} from
 *       {@link #nextSequence()} <i>when it starts</i>. A publish whose seq is not strictly greater
 *       than the stored one is dropped, so a slow build that began earlier can never clobber a
 *       newer catalogue that finished first. {@code @Version} optimistic locking on the row is the
 *       second guard, for two publishers that race on the same row.</li>
 *   <li><b>Complete beats partial.</b> A {@code complete} snapshot is never overwritten by a
 *       {@code partial} one; the attempt is still recorded as a refresh attempt.</li>
 *   <li><b>Concurrency-safe upserts.</b> Catalogue items are updated in place by their unique key
 *       first and inserted only when that touches no row, with a retry if a racing insert wins — the
 *       database constraint is the arbiter, never an app-level find-then-insert.</li>
 *   <li><b>No secrets persisted.</b> Tile playlists (which can carry signed stream URLs) are
 *       stripped before serialization; a poster that carries query parameters is treated as unsafe
 *       and stored as null, falling back to the client placeholder.</li>
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

    private final AtomicLong sequence = new AtomicLong(0);
    private volatile boolean sequenceSeeded = false;

    public MirrorCatalogueStore(MirrorCatalogueItemRepository items,
                                MirrorCatalogueRelationRepository relations,
                                MirrorCatalogueSnapshotRepository snapshots,
                                MirrorCatalogueProperties props) {
        this.items = items;
        this.relations = relations;
        this.snapshots = snapshots;
        this.props = props;
    }

    // ------------------------------------------------------------------ public API

    public boolean persistenceEnabled() {
        return props.getPersistence().isEnabled();
    }

    /** The configured, non-secret catalogue scope. Never a cookie, token or profile id. */
    public String scope() {
        return props.getScope();
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
        if (!persistenceEnabled()) {
            return Optional.empty();
        }
        String scope = scope();
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
     * item/relation tables, all in one transaction. Returns true only if this build actually became
     * the stored snapshot. A no-op (returns false) when persistence is off, when {@code rails} is
     * empty, when {@code buildSeq} is not newer than the stored one, or when a complete snapshot
     * would be overwritten by a partial one.
     *
     * @param buildSeq the token from {@link #nextSequence()} taken when this build started
     */
    @Transactional
    public boolean publish(String ott, List<MirrorRailDto> rails, boolean complete, long buildSeq) {
        if (!persistenceEnabled() || rails == null || rails.isEmpty()) {
            return false;
        }
        String scope = scope();
        String ottKey = ott(ott);
        Instant now = Instant.now();

        List<MirrorRailDto> sanitized = sanitizeRails(rails);
        String json = serialize(sanitized);
        if (json == null) {
            return false;
        }
        int itemCount = countTiles(sanitized);

        boolean stored;
        try {
            stored = writeSnapshot(scope, ottKey, json, sanitized.size(), itemCount, complete, buildSeq, now);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException race) {
            // A concurrent publisher won the row; theirs stands, ours is safely dropped.
            log.debug("catalogue: snapshot publish lost a race for {}/{}", scope, ottKey);
            return false;
        }
        if (!stored) {
            return false;
        }

        // Metadata tables are best-effort within the same transaction; the snapshot is the source
        // of truth for a warm read, so a hiccup here must not fail the publish.
        try {
            upsertItems(scope, ottKey, sanitized, now);
            upsertRelations(scope, ottKey, sanitized, now);
            enforceRetention(scope, ottKey, now);
        } catch (RuntimeException e) {
            log.warn("catalogue: item/relation refresh failed for {}/{} (snapshot kept)", scope, ottKey, e);
        }
        return true;
    }

    // ------------------------------------------------------------------ snapshot write

    private boolean writeSnapshot(String scope, String ott, String json, int railCount, int itemCount,
                                  boolean complete, long buildSeq, Instant now) {
        Optional<MirrorCatalogueSnapshot> found = snapshots.findByScopeAndOtt(scope, ott);
        if (found.isPresent()) {
            MirrorCatalogueSnapshot ex = found.get();
            if (buildSeq <= ex.getBuildSeq()) {
                // An older or equal build; the stored catalogue is at least as new.
                return false;
            }
            if (ex.isComplete() && !complete) {
                // Never let a partial build replace a complete catalogue; just note the attempt.
                snapshots.markAttempted(scope, ott, now);
                return false;
            }
            ex.setRailsJson(json);
            ex.setComplete(complete);
            ex.setBuildSeq(buildSeq);
            ex.setRailCount(railCount);
            ex.setItemCount(itemCount);
            ex.setLastSuccessfulRefresh(now);
            ex.setLastAttemptedRefresh(now);
            snapshots.saveAndFlush(ex);
            return true;
        }
        MirrorCatalogueSnapshot snap = MirrorCatalogueSnapshot.builder()
                .scope(scope)
                .ott(ott)
                .railsJson(json)
                .complete(complete)
                .buildSeq(buildSeq)
                .railCount(railCount)
                .itemCount(itemCount)
                .lastSuccessfulRefresh(now)
                .lastAttemptedRefresh(now)
                .lastAccess(now)
                .build();
        snapshots.saveAndFlush(snap);
        return true;
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
        int updated = items.touch(scope, ott, tile.id(), name, poster, null, null, null, now);
        if (updated > 0) {
            return;
        }
        MirrorCatalogueItem row = MirrorCatalogueItem.builder()
                .scope(scope)
                .ott(ott)
                .mirrorId(tile.id())
                .name(name)
                .posterUrl(poster)
                .firstSeen(now)
                .lastSeen(now)
                .build();
        try {
            items.save(row);
        } catch (DataIntegrityViolationException raced) {
            // A concurrent insert beat us to the unique key; fold our values in with an update.
            items.touch(scope, ott, tile.id(), name, poster, null, null, null, now);
        }
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
                MirrorCatalogueRelation edge = MirrorCatalogueRelation.builder()
                        .scope(scope)
                        .ott(ott)
                        .sourceRef(sourceRef)
                        .relatedId(tile.id())
                        .kind(KIND_MORE_LIKE)
                        .ordinal(ordinal++)
                        .updatedAt(now)
                        .build();
                try {
                    relations.save(edge);
                } catch (DataIntegrityViolationException ignored) {
                    // Duplicate edge under a concurrent writer; the existing one is equivalent.
                }
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
     * Keep only a poster reference safe to store. The parser's poster is the pure {@code (ott, id)}
     * CDN path; anything carrying a query string is session/signing-shaped and is dropped rather
     * than guessed at (removing the query is not assumed to yield a valid poster). The client's
     * placeholder covers a null.
     */
    private String safePoster(String image) {
        if (isBlank(image)) {
            return null;
        }
        String trimmed = image.trim();
        if (trimmed.indexOf('?') >= 0 || trimmed.length() > MirrorCatalogueItem.POSTER_MAX) {
            return null;
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
