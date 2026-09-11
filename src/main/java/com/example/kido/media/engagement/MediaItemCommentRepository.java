package com.example.kido.media.engagement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaItemCommentRepository extends JpaRepository<MediaItemComment, String> {

    /** Newest first: a comment thread on a film is read as a feed, not as a transcript. */
    Page<MediaItemComment> findByMediaItemIdOrderByCreatedAtDesc(String mediaItemId,
                                                                Pageable pageable);

    long countByMediaItemId(String mediaItemId);

    /** Every comment on an item, e.g. when the item itself is being purged. */
    void deleteByMediaItemId(String mediaItemId);
}
