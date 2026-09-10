package com.example.kido.media.dto;

/** Request and response shapes for the like button. */
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
}
