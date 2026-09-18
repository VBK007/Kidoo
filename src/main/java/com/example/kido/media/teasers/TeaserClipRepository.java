package com.example.kido.media.teasers;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeaserClipRepository extends JpaRepository<TeaserClip, String> {

    List<TeaserClip> findByMediaItemIdOrderBySortOrderAscCreatedAtDesc(String mediaItemId);

    List<TeaserClip> findByMediaItemIdAndPublishedTrueAndStateOrderBySortOrderAscCreatedAtDesc(
            String mediaItemId, TeaserClip.State state);

    Page<TeaserClip> findByPublishedTrueAndStateOrderBySortOrderAscCreatedAtDesc(
            TeaserClip.State state, Pageable pageable);

    /** Stranded jobs found on startup, so a restart mid-encode can be recovered. */
    List<TeaserClip> findByStateIn(List<TeaserClip.State> states);

    Optional<TeaserClip> findByIdAndMediaItemId(String id, String mediaItemId);

    /** Every clip for an item, e.g. when the item itself is being purged. */
    void deleteByMediaItemId(String mediaItemId);
}
