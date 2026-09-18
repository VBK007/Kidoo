package com.example.kido.media.dto;

import java.util.List;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.teasers.TeaserClip;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response shapes for teaser shorts. */
public final class TeaserClipDtos {

    private TeaserClipDtos() {}

    public record CreateTeaserClipRequest(
            @NotNull @Min(0) Double startSeconds,
            @NotNull Double endSeconds,
            @DecimalMin("-1.0") @DecimalMax("1.0") Double horizontalOffset,
            @Size(max = 256) String label,
            @Size(max = 1024) String sourceNote,
            Boolean published) {}

    public record PublishRequest(@NotNull Boolean published) {}

    /**
     * @param fileUrl only set once the clip is READY; null while queued/generating/failed
     */
    public record TeaserClipDto(
            String id,
            String mediaItemId,
            String itemTitle,
            String state,
            double startSeconds,
            double endSeconds,
            double durationSeconds,
            double horizontalOffset,
            String label,
            boolean published,
            String fileUrl,
            Integer width,
            Integer height,
            Long bytes,
            String error,
            String createdAt) {

        public static TeaserClipDto from(TeaserClip clip) {
            return from(clip, null);
        }

        public static TeaserClipDto from(TeaserClip clip, MediaItem item) {
            return new TeaserClipDto(
                    clip.getId(),
                    clip.getMediaItemId(),
                    item == null ? null : item.getTitle(),
                    clip.getState().name(),
                    clip.getStartSeconds(),
                    clip.getEndSeconds(),
                    clip.durationSeconds(),
                    clip.getHorizontalOffset(),
                    clip.getLabel(),
                    clip.isPublished(),
                    clip.isFetchable()
                            ? "/api/media/items/" + clip.getMediaItemId() + "/teasers/" + clip.getId() + "/file"
                            : null,
                    clip.getOutputWidth(),
                    clip.getOutputHeight(),
                    clip.getFileSize(),
                    clip.getError(),
                    clip.getCreatedAt().toString());
        }
    }

    /**
     * @param seed the shuffle this page was dealt from — pass it back on every later page
     *             of the same scroll, or the next page is a fresh shuffle that repeats
     *             clips already seen and skips others
     */
    public record TeaserFeedPageDto(
            List<TeaserClipDto> items,
            int seed,
            int page,
            int size,
            long totalItems,
            int totalPages) {}
}
