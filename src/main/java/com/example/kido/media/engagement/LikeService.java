package com.example.kido.media.engagement;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.dto.EngagementDtos.LikeDto;
import com.example.kido.profile.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * The like button, and the denormalised total that ranking sorts on.
 *
 * <p>{@code MediaItem.likeCount} is recomputed from the like rows after every change
 * rather than incremented in place. A counter that only ever moves by one drifts the
 * first time two devices tap the heart at once or a request is retried after a timeout,
 * and a drifted counter is invisible — it silently reorders the home screen. The extra
 * cost is one indexed {@code count(*)} per tap, which is nothing next to a wrong rail.
 */
@Slf4j
@Service
public class LikeService {

    private final MediaItemLikeRepository likes;
    private final MediaItemRepository items;

    public LikeService(MediaItemLikeRepository likes, MediaItemRepository items) {
        this.likes = likes;
        this.items = items;
    }

    /** Idempotent: liking something already liked is a no-op, not a duplicate row. */
    @Transactional
    public LikeDto like(Profile profile, String itemId) {
        requireItem(itemId);
        if (!likes.existsByProfileIdAndMediaItemId(profile.getId(), itemId)) {
            try {
                likes.saveAndFlush(MediaItemLike.builder()
                        .profileId(profile.getId())
                        .mediaItemId(itemId)
                        .build());
            } catch (DataIntegrityViolationException ex) {
                // Two taps raced past the exists check; the unique constraint held and
                // the end state is the one the caller asked for.
                log.debug("Duplicate like for profile {} on {}", profile.getId(), itemId);
            }
        }
        return new LikeDto(itemId, true, syncCount(itemId));
    }

    /** Idempotent in the same way: unliking something never liked simply reports zero. */
    @Transactional
    public LikeDto unlike(Profile profile, String itemId) {
        requireItem(itemId);
        likes.deleteByProfileIdAndMediaItemId(profile.getId(), itemId);
        return new LikeDto(itemId, false, syncCount(itemId));
    }

    @Transactional(readOnly = true)
    public LikeDto status(Profile profile, String itemId) {
        MediaItem item = requireItem(itemId);
        return new LikeDto(
                itemId,
                likes.existsByProfileIdAndMediaItemId(profile.getId(), itemId),
                item.getLikeCount());
    }

    /**
     * Which of {@code itemIds} this profile likes, as one query.
     *
     * <p>Every listing that renders hearts calls this once with the whole page.
     */
    @Transactional(readOnly = true)
    public Set<String> likedItemIds(Profile profile, Collection<String> itemIds) {
        if (profile == null || itemIds == null || itemIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(likes.findLikedItemIds(profile.getId(), List.copyOf(itemIds)));
    }

    /** Rewrites the cached total from the like rows, and returns it. */
    private long syncCount(String itemId) {
        long count = likes.countByMediaItemId(itemId);
        items.findById(itemId).ifPresent(item -> {
            if (item.getLikeCount() != count) {
                item.setLikeCount(count);
                items.save(item);
            }
        });
        return count;
    }

    /**
     * Unlike browsing, a like is accepted for an item whose file is currently missing:
     * the opinion is about the title, and an unplugged disk should not lose it.
     */
    private MediaItem requireItem(String itemId) {
        return items.findById(itemId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "Item not found"));
    }
}
