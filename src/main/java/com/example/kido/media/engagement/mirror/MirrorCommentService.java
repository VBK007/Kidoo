package com.example.kido.media.engagement.mirror;

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
import com.example.kido.media.dto.EngagementDtos.CommentDto;
import com.example.kido.media.dto.EngagementDtos.CommentPageDto;
import com.example.kido.profile.Profile;
import com.example.kido.profile.ProfileRepository;
import com.example.kido.user.AppUser;
import com.example.kido.user.Role;

import lombok.extern.slf4j.Slf4j;

/**
 * What the household says about a mirror title.
 *
 * <p>The counterpart to {@code CommentService} for titles that live on the mirror rather
 * than in this server's own catalogue. Moderation is identical in every respect — the
 * author may rewrite or remove what they wrote, a PARENT may remove (never edit) anything
 * written on their own account, names are overlaid live so a rename is reflected at once,
 * and whitespace-only bodies are refused. It reuses the same {@link CommentDto} and
 * {@link CommentPageDto} wire shapes, where the {@code mediaItemId} field carries the
 * mirror id.
 *
 * <p>The only difference from the catalogue service is that there is no item to look up: a
 * mirror id is an opaque external token, so {@link MirrorIds#require} sanity-bounds it in
 * place of the catalogue's existence check, and the comment is accepted against the id as
 * given.
 */
@Slf4j
@Service
public class MirrorCommentService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MirrorCommentRepository comments;
    private final ProfileRepository profiles;

    public MirrorCommentService(MirrorCommentRepository comments, ProfileRepository profiles) {
        this.comments = comments;
        this.profiles = profiles;
    }

    @Transactional
    public CommentDto post(Profile profile, AppUser user, String mirrorId, String body) {
        String id = MirrorIds.require(mirrorId);
        MirrorComment saved = comments.save(MirrorComment.builder()
                .mirrorId(id)
                .profileId(profile.getId())
                .ownerId(profile.getOwnerId())
                .authorName(profile.getName())
                .body(clean(body))
                .build());

        log.info("Profile {} commented on mirror title {}", profile.getId(), id);
        return toDto(saved, profile, user, currentNames(List.of(saved)));
    }

    @Transactional(readOnly = true)
    public CommentPageDto list(Profile profile, AppUser user, String mirrorId, int page, int size) {
        String id = MirrorIds.require(mirrorId);
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<MirrorComment> found = comments.findByMirrorIdOrderByCreatedAtDescIdDesc(
                id, PageRequest.of(Math.max(0, page), pageSize));

        Map<String, String> names = currentNames(found.getContent());
        List<CommentDto> dtos = found.getContent().stream()
                .map(comment -> toDto(comment, profile, user, names))
                .toList();

        return new CommentPageDto(dtos, found.getNumber(), found.getSize(),
                found.getTotalElements(), found.getTotalPages());
    }

    /** Rewriting is the author's alone — a parent moderates by removing, not by editing. */
    @Transactional
    public CommentDto edit(Profile profile, AppUser user, String mirrorId, String commentId,
                           String body) {
        MirrorComment comment = requireComment(MirrorIds.require(mirrorId), commentId);
        if (!comment.getProfileId().equals(profile.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only edit your own comment");
        }

        comment.setBody(clean(body));
        comment.setEditedAt(Instant.now());
        MirrorComment saved = comments.save(comment);
        return toDto(saved, profile, user, currentNames(List.of(saved)));
    }

    @Transactional
    public void delete(Profile profile, AppUser user, String mirrorId, String commentId) {
        MirrorComment comment = requireComment(MirrorIds.require(mirrorId), commentId);
        if (!canDelete(comment, profile, user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot delete this comment");
        }
        comments.delete(comment);
        log.info("Profile {} deleted comment {} on mirror title {}",
                profile.getId(), commentId, comment.getMirrorId());
    }

    /** How many comments a mirror title carries, for the count on its detail screen. */
    @Transactional(readOnly = true)
    public long countFor(String mirrorId) {
        return comments.countByMirrorId(MirrorIds.require(mirrorId));
    }

    /**
     * The author, or a parent of the account the author's profile belongs to.
     *
     * <p>Scoped to the account rather than to the server, so that one household's parent
     * cannot reach into another's thread on a machine shared by more than one family.
     */
    private boolean canDelete(MirrorComment comment, Profile profile, AppUser user) {
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
     * <p>Read live rather than taken from the snapshot on the row: a profile renamed last
     * week should not still be signing the comments it wrote the week before. Profiles that
     * no longer exist are simply absent, and the snapshot stands in.
     */
    private Map<String, String> currentNames(List<MirrorComment> found) {
        List<String> profileIds = found.stream()
                .map(MirrorComment::getProfileId)
                .distinct()
                .toList();
        if (profileIds.isEmpty()) {
            return Map.of();
        }
        return profiles.findAllById(profileIds).stream()
                .collect(Collectors.toMap(Profile::getId, Profile::getName, (a, b) -> a));
    }

    private CommentDto toDto(MirrorComment comment, Profile profile, AppUser user,
                             Map<String, String> names) {
        return new CommentDto(
                comment.getId(),
                comment.getMirrorId(),
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
     * <p>Bean validation has already turned away an empty body, but a body of nothing but
     * whitespace reaches here as a comment that would render as an empty bubble.
     */
    private String clean(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A comment cannot be empty");
        }
        if (trimmed.length() > MirrorComment.MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A comment cannot be longer than " + MirrorComment.MAX_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * The comment, confirmed to belong to the mirror title in the path.
     *
     * <p>Checked rather than assumed: without it a correct comment id under the wrong mirror
     * id would act on a thread the caller was not looking at.
     */
    private MirrorComment requireComment(String mirrorId, String commentId) {
        MirrorComment comment = comments.findById(commentId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "Comment not found"));
        if (!comment.getMirrorId().equals(mirrorId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Comment not found");
        }
        return comment;
    }
}
