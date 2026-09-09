package com.example.kido.media.dto;

import java.util.List;

import com.example.kido.media.downloads.DownloadJob;
import com.example.kido.media.downloads.ProfileMediaSettings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Request and response shapes for offline copies and the away-from-home decision. */
public final class DownloadDtos {

    private DownloadDtos() {}

    /**
     * Asks the server to prepare an offline copy.
     *
     * <p>The client's capabilities are included for the same reason playback sends
     * them: a file the device can already decode is handed over untouched rather than
     * needlessly re-encoded.
     */
    public record DownloadRequest(
            @Min(240) @Max(4320) Integer height,
            PlaybackDtos.ClientCapabilitiesRequest capabilities) {}

    /**
     * One row in the Saved list.
     *
     * @param label       ready-made mono line, e.g. {@code CONVERTING 4K -> 1080p · 68%}
     * @param percent     0–100; only meaningful while converting
     * @param passthrough true when the original file is being handed over unmodified
     */
    public record DownloadJobDto(
            String jobId,
            String mediaItemId,
            String itemTitle,
            String state,
            String mode,
            boolean passthrough,
            double percent,
            Integer sourceHeight,
            Integer targetHeight,
            Long bytes,
            boolean ready,
            String fileUrl,
            String label,
            String error,
            String createdAt,
            String expiresAt) {

        public static DownloadJobDto from(DownloadJob job) {
            boolean passthrough = job.getMode() == DownloadJob.Mode.PASSTHROUGH;
            return new DownloadJobDto(
                    job.getId(),
                    job.getMediaItemId(),
                    job.getItemTitle(),
                    job.getState().name(),
                    job.getMode().name(),
                    passthrough,
                    job.getPercent(),
                    job.getSourceHeight(),
                    job.getTargetHeight(),
                    job.reportedBytes(),
                    job.isFetchable(),
                    job.isFetchable() ? "/api/media/downloads/" + job.getId() + "/file" : null,
                    label(job),
                    job.getError(),
                    job.getCreatedAt().toString(),
                    job.getExpiresAt() == null ? null : job.getExpiresAt().toString());
        }

        /**
         * The mono line the client renders, built server-side so the wording of a
         * conversion and its percentage cannot disagree.
         */
        private static String label(DownloadJob job) {
            return switch (job.getState()) {
                case QUEUED -> "WAITING TO CONVERT";
                case CONVERTING -> "CONVERTING " + describeHeight(job.getSourceHeight())
                        + " -> " + describeHeight(job.getTargetHeight())
                        + " · " + Math.round(job.getPercent()) + "%";
                case READY -> job.getMode() == DownloadJob.Mode.PASSTHROUGH
                        ? "SAVED · ORIGINAL QUALITY"
                        : "SAVED · " + describeHeight(job.getTargetHeight());
                case FAILED -> "FAILED";
                case CANCELLED -> "CANCELLED";
                case EXPIRED -> "EXPIRED · REQUEST AGAIN";
            };
        }

        /** 2160 reads as 4K to a person, everything else as its own number. */
        private static String describeHeight(Integer height) {
            if (height == null) {
                return "SOURCE";
            }
            return height >= 2160 ? "4K" : height + "P";
        }
    }

    public record DownloadListDto(
            List<DownloadJobDto> items,
            int readyCount,
            int inProgressCount,
            long readyBytes) {}

    /**
     * Whether the caller is on the home network.
     *
     * @param uploadBitsPerSecond assumed home upload when away, for cost estimates
     * @param suggestedHeight     resolution that fits that upload
     */
    public record ReachabilityDto(
            boolean atHome,
            String location,
            String clientAddress,
            String explanation,
            Long uploadBitsPerSecond,
            Integer suggestedHeight) {}

    /**
     * What playing a title would cost, for the stream-or-saved sheet.
     *
     * @param originalBytes   the file as it stands
     * @param transcodedBytes an estimate for a connection-sized version
     */
    public record PlaybackCostDto(
            String mediaItemId,
            boolean atHome,
            Long originalBytes,
            Long transcodedBytes,
            Integer transcodeHeight,
            Long uploadBitsPerSecond,
            boolean originalFitsUpload,
            String explanation) {}

    public record MediaSettingsDto(
            String profileId,
            String awayBehaviour,
            Integer downloadHeight,
            Integer awayMaxHeight,
            boolean showTechnicalBadges) {

        public static MediaSettingsDto from(ProfileMediaSettings settings) {
            return new MediaSettingsDto(
                    settings.getProfileId(),
                    settings.getAwayBehaviour().name(),
                    settings.getDownloadHeight(),
                    settings.getAwayMaxHeight(),
                    settings.isShowTechnicalBadges());
        }
    }

    /** Every field optional: the client sends only what changed. */
    public record UpdateMediaSettingsRequest(
            String awayBehaviour,
            @Min(240) @Max(4320) Integer downloadHeight,
            @Min(240) @Max(4320) Integer awayMaxHeight,
            Boolean showTechnicalBadges) {}
}
