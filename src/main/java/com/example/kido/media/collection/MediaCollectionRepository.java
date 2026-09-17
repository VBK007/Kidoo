package com.example.kido.media.collection;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Collections are per account and few — a household writes a handful, not thousands —
 * so these are unpaged by design. If that ever stops being true the list endpoint is
 * where it would show, not here.
 */
public interface MediaCollectionRepository extends JpaRepository<MediaCollection, String> {

    List<MediaCollection> findByOwnerIdOrderBySortOrderAscNameAsc(String ownerId);

    Optional<MediaCollection> findByIdAndOwnerId(String id, String ownerId);

    /** The row remembering that a built-in was pinned or renamed, if there is one. */
    Optional<MediaCollection> findByOwnerIdAndBuiltinKey(String ownerId, String builtinKey);

    List<MediaCollection> findByOwnerIdAndPinnedTrueOrderBySortOrderAscNameAsc(String ownerId);
}
