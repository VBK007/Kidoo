package com.example.kido.media.engagement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaItemCommentRepository extends JpaRepository<MediaItemComment, String> {

    /**
     * Newest first: a comment thread on a film is read as a feed, not as a transcript.
     *
     * <p>The id breaks ties, and it has to. {@code createdAt} is not unique: the clock
     * behind {@code Instant.now()} advances in ticks rather than continuously — on
     * Windows roughly every 15ms, during which every call returns the same value — so
     * two comments posted in the same breath genuinely carry the same timestamp.
     *
     * <p>Without a second column the order within such a group is whatever the database
     * feels like, and it need not be the same twice. That is not a cosmetic problem: page
     * 1 and page 2 are separate queries, so an unstable order can show one comment on
     * both pages and drop another entirely.
     *
     * <p>The id is a random UUID, so it orders arbitrarily rather than by insertion — but
     * arbitrary and fixed is what paging needs, and comments sharing a tick have no
     * knowable order anyway. Restoring true insertion order would take a sequence column,
     * which is a schema change for something no reader could tell apart.
     */
    Page<MediaItemComment> findByMediaItemIdOrderByCreatedAtDescIdDesc(String mediaItemId,
                                                                      Pageable pageable);

    long countByMediaItemId(String mediaItemId);

    /** Every comment on an item, e.g. when the item itself is being purged. */
    void deleteByMediaItemId(String mediaItemId);
}
