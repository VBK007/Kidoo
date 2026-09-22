package com.example.kido.media.teasers;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TeaserClipRepository extends JpaRepository<TeaserClip, String> {

    List<TeaserClip> findByMediaItemIdOrderBySortOrderAscCreatedAtDesc(String mediaItemId);

    List<TeaserClip> findByMediaItemIdAndPublishedTrueAndStateOrderBySortOrderAscCreatedAtDesc(
            String mediaItemId, TeaserClip.State state);

    /**
     * The clips pinned to the top of the global feed, in the order they were pinned in.
     *
     * <p>{@code sortOrder} is the one piece of hand curation the feed has, so it survives
     * the shuffle: somebody who ranked a clip meant it to be seen first, and a random
     * order that ignored that would quietly turn the field off. Everything else —
     * {@code sortOrder = 0}, which is nearly every clip — is shuffled instead.
     *
     * <p>{@code createdAt} and then the id break ties within one rank, for the same
     * reason a comment thread needs them: two clips can share a rank, and a page is its
     * own query, so an order that need not agree between two pages shows one clip twice
     * and loses another.
     */
    @Query("""
            select c.id from TeaserClip c
            where c.published = true and c.state = :state and c.sortOrder <> 0
            order by c.sortOrder asc, c.createdAt desc, c.id desc
            """)
    List<String> findPinnedFeedIds(@Param("state") TeaserClip.State state);

    /**
     * Every unpinned clip in the global feed, by id — the set the shuffle draws from.
     *
     * <p>Ids rather than rows because the shuffle has to see the whole feed before it
     * can hand out any one page of it, and a clip is a wide row to load for that. The
     * page's rows are fetched afterwards, once the ids on it are known.
     *
     * <p>Ordered by id, which is arbitrary — and has to be, for the shuffle to mean
     * anything. What matters is that it is <em>fixed</em>: the same seed shuffling the
     * same list gives the same order, which is what lets page 2 continue page 1 instead
     * of re-dealing the deck.
     */
    @Query("""
            select c.id from TeaserClip c
            where c.published = true and c.state = :state and c.sortOrder = 0
            order by c.id
            """)
    List<String> findShuffleableFeedIds(@Param("state") TeaserClip.State state);

    /** Stranded jobs found on startup, so a restart mid-encode can be recovered. */
    List<TeaserClip> findByStateIn(List<TeaserClip.State> states);

    Optional<TeaserClip> findByIdAndMediaItemId(String id, String mediaItemId);

    /**
     * Every clip for an item, e.g. when the item itself is being purged.
     *
     * <p>{@code @Transactional} here specifically: unlike the base {@code save}/{@code
     * findById} CRUD methods, a derived delete query does not get its own transaction
     * for free, and this is called from {@link TeaserClipService#queueAutoTeasers}
     * with none open around it on purpose (see that method's own docs).
     */
    @Transactional
    void deleteByMediaItemId(String mediaItemId);

    /**
     * Every media item that has at least one clip not stuck in {@code FAILED}, so a
     * backfill pass over the whole library knows which titles to leave alone.
     *
     * <p>{@code FAILED} does not count: a title where all three auto-picked cuts
     * failed (the source unreachable, most often — an external drive dropping mid-cut)
     * has no usable clip either, and ought to get another try on a later scan rather
     * than being treated as done forever because a row exists.
     */
    @Query("select distinct c.mediaItemId from TeaserClip c where c.state <> :failed")
    Set<String> mediaItemIdsWithUsableClips(@Param("failed") TeaserClip.State failed);
}
