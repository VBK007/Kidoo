package com.example.kido.media.dto;

import java.util.List;

import com.example.kido.media.engagement.MediaItemComment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response shapes for the like button and the comment thread. */
public final class EngagementDtos {

    private EngagementDtos() {}

    /**
     * The state of one item's like button after a change.
     *
     * @param liked     whether the calling profile likes it now
     * @param likeCount the household total, so the client can update the label without
     *                  refetching the item
     */
    public record LikeDto(String mediaItemId, boolean liked, long likeCount) {}

    /** What a client sends to post or rewrite a comment. */
    public record CommentRequest(
            @NotBlank @Size(max = MediaItemComment.MAX_LENGTH) String body
    ) {}

    /**
     * One comment, ready to render.
     *
     * @param authorName the author profile's current name, falling back to the name it
     *                   had when the comment was written if the profile is gone
     * @param editedAt   null while the comment stands as first posted, so the client can
     *                   show an "edited" mark without comparing timestamps
     * @param mine       whether the calling profile wrote it — which avatar side it sits
     *                   on, and whether an edit control is offered
     * @param canDelete  true for the author, and for a parent on the same account: the
     *                   client should not offer a delete the server will refuse
     */
    public record CommentDto(
            String id,
            String mediaItemId,
            String profileId,
            String authorName,
            String body,
            String createdAt,
            String editedAt,
            boolean mine,
            boolean canDelete) {}

    /** A page of a title's comments, newest first. */
    public record CommentPageDto(
            List<CommentDto> comments,
            int page,
            int size,
            long totalItems,
            int totalPages) {}
}
