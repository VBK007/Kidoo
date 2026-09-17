package com.example.kido.media.collection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.dto.CatalogDtos.ItemPageDto;
import com.example.kido.media.dto.CollectionDtos.CollectionDto;
import com.example.kido.media.dto.CollectionDtos.CollectionKind;
import com.example.kido.media.dto.CollectionDtos.CollectionListDto;
import com.example.kido.media.dto.CollectionDtos.CollectionRequest;
import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.query.CatalogQuerySpecs;
import com.example.kido.profile.Profile;
import com.example.kido.user.AppUser;

import lombok.extern.slf4j.Slf4j;

/**
 * Collections: the ones the server ships, the ones the owner writes, and the ones the
 * library turns out to already contain.
 *
 * <p>All three are the same thing behind the DTO — a name and a {@link CatalogQuery} —
 * which is why one endpoint can open any of them and why none of this needed the catalog
 * to learn anything new. What differs is only where the definition lives and who may
 * change it.
 *
 * <p>Addressing reflects that: a custom collection is a row id, a built-in is
 * {@code builtin:<key>}, a discovered one is {@code discovered:<key>}. A client holds
 * one string and does not have to remember which sort it has.
 */
@Slf4j
@Service
public class CollectionService {

    private static final String BUILTIN_PREFIX = "builtin:";
    private static final String DISCOVERED_PREFIX = "discovered:";

    /** A name has to fit a rail heading; beyond this it is not a name, it is a note. */
    private static final int MAX_NAME_LENGTH = 128;

    private final MediaCollectionRepository collections;
    private final DiscoveredCollections discovered;
    private final CollectionQueryCodec codec;
    private final CatalogService catalog;
    private final MediaItemRepository items;

    public CollectionService(MediaCollectionRepository collections,
                             DiscoveredCollections discovered,
                             CollectionQueryCodec codec,
                             CatalogService catalog,
                             MediaItemRepository items) {
        this.collections = collections;
        this.discovered = discovered;
        this.codec = codec;
        this.catalog = catalog;
        this.items = items;
    }

    // --- listing ---

    /**
     * Every collection available to this account.
     *
     * <p>Counted per collection, which is one count query each. Worth it: a collection
     * with no members is one a client should grey out or hide, and it cannot know that
     * without asking. Discovered collections come with their counts already — they were
     * produced by counting — so only the built-ins and the custom ones cost anything.
     */
    @Transactional(readOnly = true)
    public CollectionListDto list(AppUser owner, Profile profile) {
        Map<String, MediaCollection> byBuiltinKey = new LinkedHashMap<>();
        List<MediaCollection> custom = new ArrayList<>();
        for (MediaCollection row : collections.findByOwnerIdOrderBySortOrderAscNameAsc(
                owner.getId())) {
            if (row.isBuiltin()) {
                byBuiltinKey.put(row.getBuiltinKey(), row);
            } else {
                custom.add(row);
            }
        }

        List<CollectionDto> builtin = new ArrayList<>();
        for (BuiltinCollection definition : BuiltinCollection.values()) {
            builtin.add(toDto(definition, byBuiltinKey.get(definition.key()), profile));
        }

        List<CollectionDto> written = custom.stream()
                .map(row -> toDto(row, profile))
                .toList();

        List<CollectionDto> found = discovered.all().stream()
                .map(d -> new CollectionDto(DISCOVERED_PREFIX + d.key(),
                        CollectionKind.DISCOVERED, d.name(), d.icon(), false,
                        d.itemCount(), d.query()))
                .toList();

        return new CollectionListDto(builtin, written, found);
    }

    /** The rails a pinned collection earns on the home screen, in the owner's order. */
    @Transactional(readOnly = true)
    public List<CollectionDto> pinned(AppUser owner, Profile profile) {
        List<CollectionDto> rails = new ArrayList<>();
        for (MediaCollection row : collections
                .findByOwnerIdAndPinnedTrueOrderBySortOrderAscNameAsc(owner.getId())) {
            rails.add(row.isBuiltin()
                    ? BuiltinCollection.byKey(row.getBuiltinKey())
                            .map(definition -> toDto(definition, row, profile))
                            .orElse(null)
                    : toDto(row, profile));
        }
        rails.removeIf(java.util.Objects::isNull);
        return rails;
    }

    // --- opening one ---

    /** A page of the titles a collection holds. */
    @Transactional(readOnly = true)
    public ItemPageDto items(AppUser owner, Profile profile, String id, int page, int size) {
        return catalog.search(profile, queryOf(owner, id), page, size);
    }

    /** The collection itself, whatever sort it is. */
    @Transactional(readOnly = true)
    public CollectionDto get(AppUser owner, Profile profile, String id) {
        if (id.startsWith(DISCOVERED_PREFIX)) {
            String key = id.substring(DISCOVERED_PREFIX.length());
            return discovered.byKey(key)
                    .map(d -> new CollectionDto(id, CollectionKind.DISCOVERED, d.name(),
                            d.icon(), false, d.itemCount(), d.query()))
                    .orElseThrow(CollectionService::notFound);
        }
        if (id.startsWith(BUILTIN_PREFIX)) {
            String key = id.substring(BUILTIN_PREFIX.length());
            BuiltinCollection definition =
                    BuiltinCollection.byKey(key).orElseThrow(CollectionService::notFound);
            return toDto(definition,
                    collections.findByOwnerIdAndBuiltinKey(owner.getId(), key).orElse(null),
                    profile);
        }
        return toDto(require(owner, id), profile);
    }

    // --- writing ---

    @Transactional
    public CollectionDto create(AppUser owner, Profile profile, CollectionRequest request) {
        if (request.query() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A collection needs a query — that is what a collection is");
        }
        CatalogQuery query = request.query().validated();

        MediaCollection row = collections.save(MediaCollection.builder()
                .ownerId(owner.getId())
                // Scoped to the profile only when the query actually asks about one,
                // so "4K HDR" stays the household's while "films I liked" does not.
                .profileId(isProfileSpecific(query) && profile != null ? profile.getId() : null)
                .name(requireName(request.name()))
                .icon(request.icon())
                .queryJson(codec.write(query))
                .pinned(Boolean.TRUE.equals(request.pinned()))
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
                .build());

        log.info("Created collection '{}' for account={}", row.getName(), owner.getId());
        return toDto(row, profile);
    }

    /**
     * Updates a custom collection, or the pin and name of a built-in.
     *
     * <p>A built-in has no row until this is called on one — the first pin or rename is
     * what creates it. That is why a fresh install has none and still shows all of them.
     */
    @Transactional
    public CollectionDto update(AppUser owner, Profile profile, String id,
                                CollectionRequest request) {
        if (id.startsWith(DISCOVERED_PREFIX)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A discovered collection is the library describing itself; "
                            + "save it as your own to change it");
        }
        if (id.startsWith(BUILTIN_PREFIX)) {
            return updateBuiltin(owner, profile, id.substring(BUILTIN_PREFIX.length()), request);
        }

        MediaCollection row = require(owner, id);
        if (request.name() != null) {
            row.setName(requireName(request.name()));
        }
        if (request.icon() != null) {
            row.setIcon(request.icon());
        }
        if (request.pinned() != null) {
            row.setPinned(request.pinned());
        }
        if (request.sortOrder() != null) {
            row.setSortOrder(request.sortOrder());
        }
        if (request.query() != null) {
            CatalogQuery query = request.query().validated();
            row.setQueryJson(codec.write(query));
            row.setProfileId(isProfileSpecific(query) && profile != null
                    ? profile.getId() : null);
        }
        row.setUpdatedAt(Instant.now());
        return toDto(collections.save(row), profile);
    }

    private CollectionDto updateBuiltin(AppUser owner, Profile profile, String key,
                                        CollectionRequest request) {
        BuiltinCollection definition =
                BuiltinCollection.byKey(key).orElseThrow(CollectionService::notFound);
        if (request.query() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "'" + definition.defaultName() + "' is built in and its filter cannot "
                            + "be changed; save your own collection instead");
        }

        MediaCollection row = collections.findByOwnerIdAndBuiltinKey(owner.getId(), key)
                .orElseGet(() -> MediaCollection.builder()
                        .ownerId(owner.getId())
                        .builtinKey(key)
                        .build());

        if (request.name() != null) {
            row.setName(requireName(request.name()));
        }
        if (request.icon() != null) {
            row.setIcon(request.icon());
        }
        if (request.pinned() != null) {
            row.setPinned(request.pinned());
        }
        if (request.sortOrder() != null) {
            row.setSortOrder(request.sortOrder());
        }
        row.setUpdatedAt(Instant.now());
        return toDto(definition, collections.save(row), profile);
    }

    /**
     * Deletes a custom collection, or forgets the pin and rename on a built-in.
     *
     * <p>Deleting a built-in's row restores it rather than removing it, which is the
     * only sensible reading of "delete" on something that ships with the server.
     */
    @Transactional
    public void delete(AppUser owner, String id) {
        if (id.startsWith(DISCOVERED_PREFIX)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A discovered collection has nothing to delete — it is the library "
                            + "describing itself");
        }
        if (id.startsWith(BUILTIN_PREFIX)) {
            collections.findByOwnerIdAndBuiltinKey(
                            owner.getId(), id.substring(BUILTIN_PREFIX.length()))
                    .ifPresent(collections::delete);
            return;
        }
        collections.delete(require(owner, id));
    }

    // --- helpers ---

    /** The query behind any id, which is the only thing opening one actually needs. */
    private CatalogQuery queryOf(AppUser owner, String id) {
        if (id.startsWith(DISCOVERED_PREFIX)) {
            return discovered.byKey(id.substring(DISCOVERED_PREFIX.length()))
                    .map(DiscoveredCollections.Discovered::query)
                    .orElseThrow(CollectionService::notFound);
        }
        if (id.startsWith(BUILTIN_PREFIX)) {
            return BuiltinCollection.byKey(id.substring(BUILTIN_PREFIX.length()))
                    .map(BuiltinCollection::query)
                    .orElseThrow(CollectionService::notFound);
        }
        MediaCollection row = require(owner, id);
        return codec.read(row.getQueryJson(), displayName(row, null));
    }

    private MediaCollection require(AppUser owner, String id) {
        return collections.findByIdAndOwnerId(id, owner.getId())
                .orElseThrow(CollectionService::notFound);
    }

    private CollectionDto toDto(MediaCollection row, Profile profile) {
        CatalogQuery query = codec.read(row.getQueryJson(), displayName(row, null));
        return new CollectionDto(row.getId(), CollectionKind.CUSTOM,
                displayName(row, null), row.getIcon(), row.isPinned(),
                count(query, profile), query);
    }

    private CollectionDto toDto(BuiltinCollection definition, MediaCollection row,
                                Profile profile) {
        CatalogQuery query = definition.query();
        return new CollectionDto(BUILTIN_PREFIX + definition.key(), CollectionKind.BUILTIN,
                displayName(row, definition),
                row != null && row.getIcon() != null ? row.getIcon() : definition.defaultIcon(),
                row != null && row.isPinned(),
                count(query, profile), query);
    }

    /** The owner's name for it if they gave one, else the built-in's own. */
    private static String displayName(MediaCollection row, BuiltinCollection definition) {
        if (row != null && row.getName() != null && !row.getName().isBlank()) {
            return row.getName();
        }
        return definition == null ? "Untitled collection" : definition.defaultName();
    }

    private long count(CatalogQuery query, Profile profile) {
        return items.count(CatalogQuerySpecs.toSpecification(
                query, profile == null ? null : profile.getId()));
    }

    /**
     * True when the query asks about a particular person rather than about the library.
     *
     * <p>Only these two filters read per-profile state, so only they make a collection
     * mean different things on different phones — which is exactly when it should be
     * pinned to the profile that created it.
     */
    private static boolean isProfileSpecific(CatalogQuery query) {
        boolean watchIsPersonal = query.watched() == CatalogQuery.WatchedBy.ME
                || query.watched() == CatalogQuery.WatchedBy.NOT_ME;
        return watchIsPersonal || query.liked() != null;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A collection needs a name");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "That name is too long for a rail heading (max " + MAX_NAME_LENGTH + ")");
        }
        return name.trim();
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "No such collection");
    }
}
