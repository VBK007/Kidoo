package com.example.kido.media.engagement.mirror;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.dto.EngagementDtos.LikeDto;
import com.example.kido.profile.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * The like button for mirror titles.
 *
 * <p>The counterpart to {@code LikeService} for titles that live on the mirror rather than
 * in this server's own catalogue. It behaves the same from the client's side — idempotent
 * like/unlike, a filled-or-empty heart per profile — and reuses the same {@link LikeDto}
 * wire shape, where the {@code mediaItemId} field carries the mirror id.
 *
 * <p>The one structural difference is where the total comes from. A catalogue like
 * denormalises its count onto {@code MediaItem.likeCount} so ranking is an indexed sort; a
 * mirror title has no such row, so the total is a {@code count(*)} over the like rows,
 * returned fresh each time. There is nowhere to cache it and nothing here that ranks on it,
 * so that is exactly right rather than a compromise.
 *
 * <p>There is also no existence check. A catalogue like first proves the item is a real
 * {@code media_items} row; a mirror id is an opaque external token with no row to find, so
 * {@link MirrorIds#require} only sanity-bounds it and the like is accepted as-is.
 */
@Slf4j
@Service
public class MirrorLikeService {

    private final MirrorLikeRepository likes;

    public MirrorLikeService(MirrorLikeRepository likes) {
        this.likes = likes;
    }

    /** Idempotent: liking something already liked is a no-op, not a duplicate row. */
    @Transactional
    public LikeDto like(Profile profile, String mirrorId) {
        String id = MirrorIds.require(mirrorId);
        if (!likes.existsByProfileIdAndMirrorId(profile.getId(), id)) {
            try {
                likes.saveAndFlush(MirrorLike.builder()
                        .profileId(profile.getId())
                        .mirrorId(id)
                        .build());
            } catch (DataIntegrityViolationException ex) {
                // Two taps raced past the exists check; the unique constraint held and the
                // end state is the one the caller asked for.
                log.debug("Duplicate mirror like for profile {} on {}", profile.getId(), id);
            }
        }
        return new LikeDto(id, true, likes.countByMirrorId(id));
    }

    /** Idempotent in the same way: unliking something never liked simply reports zero. */
    @Transactional
    public LikeDto unlike(Profile profile, String mirrorId) {
        String id = MirrorIds.require(mirrorId);
        likes.deleteByProfileIdAndMirrorId(profile.getId(), id);
        return new LikeDto(id, false, likes.countByMirrorId(id));
    }

    @Transactional(readOnly = true)
    public LikeDto status(Profile profile, String mirrorId) {
        String id = MirrorIds.require(mirrorId);
        return new LikeDto(
                id,
                likes.existsByProfileIdAndMirrorId(profile.getId(), id),
                likes.countByMirrorId(id));
    }

    /**
     * Which of {@code mirrorIds} this profile likes, as one query.
     *
     * <p>Every mirror rail that renders hearts calls this once with the whole page, exactly
     * as the catalogue listings do.
     */
    @Transactional(readOnly = true)
    public Set<String> likedMirrorIds(Profile profile, Collection<String> mirrorIds) {
        if (profile == null || mirrorIds == null || mirrorIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(likes.findLikedMirrorIds(profile.getId(), List.copyOf(mirrorIds)));
    }
}
