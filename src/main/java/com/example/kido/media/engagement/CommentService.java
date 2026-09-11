package com.example.kido.media.engagement;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.dto.EngagementDtos.CommentDto;
import com.example.kido.media.dto.EngagementDtos.CommentPageDto;
import com.example.kido.profile.Profile;
import com.example.kido.profile.ProfileRepository;
import com.example.kido.user.AppUser;
import com.example.kido.user.Role;

import lombok.extern.slf4j.Slf4j;

/**
 * What the household says about a title.
 *
 * <p>Authorship is a profile, moderation is an account. The author may rewrite or
 * remove what they wrote; a PARENT may remove anything written on their own account,
 * which is the whole of moderation here — a parent can take something down but cannot
 * put words in a child's mouth by editing it.
 */
@Slf4j
@Service
public class CommentService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MediaItemCommentRepository comments;
    private final MediaItemRepository items;
    private final ProfileRepository profiles;

    public CommentService(MediaItemCommentRepository comments,
                          MediaItemRepository items,
                          ProfileRepository profiles) {
        this.comments = comments;
        this.items = items;
        this.profiles = profiles;
    }

    @Transactional
    public CommentDto post(Profile profile, AppUser user, String itemId, String body) {
        requireItem(itemId);
        MediaItemComment saved = comments.save(MediaItemComment.builder()
                .mediaItemId(itemId)
                .profileId(profile.getId())
                .ownerId(profile.getOwnerId())
                .authorName(profile.getName())
                .body(clean(body))
                .build());

        log.info("Profile {} commented on item {}", profile.getId(), itemId);
        return toDto(saved, profile, user, currentNames(List.of(saved)));
    }

    @Transactional(readOnly = true)
    public CommentPageDto list(Profile profile, AppUser user, String itemId, int page, int size) {
        requireItem(itemId);
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<MediaItemComment> found = comments.findByMediaItemIdOrderByCreatedAtDesc(
                itemId, PageRequest.of(Math.max(0, page), pageSize));

        Map<String, String> names = currentNames(found.getContent());
        List<CommentDto> dtos = found.getContent().stream()
                .map(comment -> toDto(comment, profile, user, names))
                .toList();

        return new CommentPageDto(dtos, found.getNumber(), found.getSize(),
                found.getTotalElements(), found.getTotalPages());
    }

    /** Rewriting is the author's alone — a parent moderates by removing, not by editing. */
    @Transactional
    public CommentDto edit(Profile profile, AppUser user, String itemId, String commentId,
                           String body) {
        MediaItemComment comment = requireComment(itemId, commentId);
        if (!comment.getProfileId().equals(profile.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only edit your own comment");
        }

        comment.setBody(clean(body));
        comment.setEditedAt(Instant.now());
        MediaItemComment saved = comments.save(comment);
        return toDto(saved, profile, user, currentNames(List.of(saved)));
    }

    @Transactional
    public void delete(Profile profile, AppUser user, String itemId, String commentId) {
        MediaItemComment comment = requireComment(itemId, commentId);
        if (!canDelete(comment, profile, user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot delete this comment");
        }
        comments.delete(comment);
        log.info("Profile {} deleted comment {} on item {}", profile.getId(), commentId, itemId);
    }

    /** How many comments a title carries, for the count on its detail screen. */
    @Transactional(readOnly = true)
    public long countFor(String itemId) {
        return comments.countByMediaItemId(itemId);
    }

    /**
     * The author, or a parent of the account the author's profile belongs to.
     *
     * <p>Scoped to the account rather than to the server, so that one household's parent
     * cannot reach into another's thread on a machine shared by more than one family.
     */
    private boolean canDelete(MediaItemComment comment, Profile profile, AppUser user) {
        if (comment.getProfileId().equals(profile.getId())) {
            return true;
        }
        return user != null
                && user.getRole() == Role.PARENT
                && comment.getOwnerId().equals(user.getId());
    }

    /**
     * Current names for the profiles behind these comments, in one query.
     *
     * <p>Read live rather than taken from the snapshot on the row: a profile renamed
     * last week should not still be signing the comments it wrote the week before.
     * Profiles that no longer exist are simply absent, and the snapshot stands in.
     */
    private Map<String, String> currentNames(List<MediaItemComment> found) {
        List<String> profileIds = found.stream()
                .map(MediaItemComment::getProfileId)
                .distinct()
                .toList();
        if (profileIds.isEmpty()) {
            return Map.of();
        }
        return profiles.findAllById(profileIds).stream()
                .collect(Collectors.toMap(Profile::getId, Profile::getName, (a, b) -> a));
    }

    private CommentDto toDto(MediaItemComment comment, Profile profile, AppUser user,
                             Map<String, String> names) {
        return new CommentDto(
                comment.getId(),
                comment.getMediaItemId(),
                comment.getProfileId(),
                names.getOrDefault(comment.getProfileId(), comment.getAuthorName()),
                comment.getBody(),
                comment.getCreatedAt().toString(),
                comment.getEditedAt() == null ? null : comment.getEditedAt().toString(),
                comment.getProfileId().equals(profile.getId()),
                canDelete(comment, profile, user));
    }

    /**
     * Trimmed, and refused if that leaves nothing.
     *
     * <p>Bean validation has already turned away an empty body, but a body of nothing
     * but whitespace reaches here as a comment that would render as an empty bubble.
     */
    private String clean(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A comment cannot be empty");
        }
        if (trimmed.length() > MediaItemComment.MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A comment cannot be longer than " + MediaItemComment.MAX_LENGTH
                            + " characters");
        }
        return trimmed;
    }

    /** Comments are taken on an item whose file is missing, for the reason likes are. */
    private MediaItem requireItem(String itemId) {
        return items.findById(itemId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "Item not found"));
    }

    /**
     * The comment, confirmed to belong to the item in the path.
     *
     * <p>Checked rather than assumed: without it a correct comment id under the wrong
     * item id would act on a thread the caller was not looking at.
     */
    private MediaItemComment requireComment(String itemId, String commentId) {
        MediaItemComment comment = comments.findById(commentId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "Comment not found"));
        if (!comment.getMediaItemId().equals(itemId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Comment not found");
        }
        return comment;
    }
}
