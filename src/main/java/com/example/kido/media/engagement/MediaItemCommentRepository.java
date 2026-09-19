package com.example.kido.media.engagement;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.kido.media.catalog.MediaType;

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

    /**
     * Fresh comments per item, for a "this week" ranking — {@link #countByMediaItemId}
     * is a lifetime total, and a thread from months ago says nothing about what people
     * are talking about now.
     */
    @Query("""
            select c.mediaItemId, count(c) from MediaItemComment c
            where c.mediaItemId in (
                select m.id from MediaItem m
                where m.missing = false and m.hidden = false and m.type in :types)
              and c.createdAt >= :since
            group by c.mediaItemId
            """)
    List<Object[]> countsByItemSince(@Param("types") List<MediaType> types, @Param("since") Instant since);
}
