package com.example.kido.media.dto;

import java.util.List;

import com.example.kido.media.query.CatalogQuery;

/**
 * Collections on the wire.
 *
 * <p>Each one carries the query that produced it. That is not debug output — it is what
 * lets a client show a collection as removable chips, let someone tweak one into a new
 * one, and explain why a title is in a list. A collection whose definition you cannot
 * see is a list you have to take on faith.
 */
public final class CollectionDtos {

    private CollectionDtos() {}

    /** Where a collection came from, which decides what may be done to it. */
    public enum CollectionKind {
        /** Ships with the server. Renameable and pinnable; the filter is not editable. */
        BUILTIN,
        /** Written by the owner. Fully editable. */
        CUSTOM,
        /** Found in the library's own facets. Read-only, and disappears if the library does. */
        DISCOVERED
    }

    /**
     * @param id        addressable: a uuid for a custom one, {@code builtin:<key>} or
     *                  {@code discovered:<key>} otherwise — so one endpoint can open any
     *                  of them without the client tracking which sort it has
     * @param itemCount how many titles match right now; a collection is a live query, so
     *                  this moves as the library does
     * @param pinned    pinned collections get a rail on the home screen
     */
    public record CollectionDto(
            String id,
            CollectionKind kind,
            String name,
            String icon,
            boolean pinned,
            long itemCount,
            CatalogQuery query) {}

    /** The list, split by kind so a client can render three sections without grouping. */
    public record CollectionListDto(
            List<CollectionDto> builtin,
            List<CollectionDto> custom,
            List<CollectionDto> discovered) {}

    /**
     * Create or update. A null field on an update means "leave it", which is what lets
     * a pin toggle be a one-field request rather than a full round trip.
     *
     * @param query required on create, optional on update, and rejected on a built-in,
     *              whose filter is code
     */
    public record CollectionRequest(
            String name,
            String icon,
            Boolean pinned,
            Integer sortOrder,
            CatalogQuery query) {}
}
