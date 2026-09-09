package com.example.kido.media.dto;

import java.util.List;

import com.example.kido.media.dto.CatalogDtos.MediaInfoDto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Request and response shapes for deciding and reporting playback. */
public final class PlaybackDtos {

    private PlaybackDtos() {}

    /**
     * What the client can decode, sent before playback starts.
     *
     * <p>The server cannot infer any of this: two Android phones of the same age differ
     * on HEVC, and iOS refuses Matroska outright regardless of the codecs inside it.
     * Everything is optional, and a field left null is treated as "unknown", which
     * pushes the decision toward transcoding rather than a stall on the user's device.
     */
    public record ClientCapabilitiesRequest(
            /**
             * Human-readable device label, e.g. "Arun's iPad". The admin panel names
             * the device that is costing CPU, which is only possible if the client says
             * who it is; there is nothing in an HTTP request that reliably identifies a
             * phone.
             */
            String deviceName,
            List<String> videoCodecs,
            List<String> audioCodecs,
            List<String> containers,
            @Min(240) @Max(4320) Integer maxHeight,
            @Min(100_000) Long maxBitrate,
            Boolean supportsHls) {

        public List<String> videoCodecsOrEmpty() {
            return videoCodecs == null ? List.of() : videoCodecs;
        }

        public List<String> audioCodecsOrEmpty() {
            return audioCodecs == null ? List.of() : audioCodecs;
        }

        public List<String> containersOrEmpty() {
            return containers == null ? List.of() : containers;
        }

        public boolean hlsAllowed() {
            return supportsHls == null || supportsHls;
        }
    }

    /** How the client should play a title, and why. */
    public record PlaybackDecisionDto(
            String mediaItemId,
            Mode mode,
            String url,
            String sessionId,
            Double startSeconds,
            MediaInfoDto mediaInfo,
            List<String> reasons) {

        public enum Mode {
            /** Serve the original bytes over HTTP range requests. */
            DIRECT,
            /** Re-encode on the fly and serve HLS. */
            TRANSCODE
        }
    }

    /** Client-reported playback position, sent periodically and on pause/stop. */
    public record ProgressRequest(
            @Min(0) double positionSeconds,
            Double durationSeconds,
            Boolean finished) {}

    public record ProgressDto(
            String mediaItemId,
            double positionSeconds,
            Double durationSeconds,
            boolean watched,
            Integer percentComplete,
            Double subtitleOffsetSeconds,
            Integer subtitleTrackIndex,
            Integer audioTrackIndex,
            String updatedAt) {}

    /** One entry in the "continue watching" row. */
    public record ContinueWatchingDto(
            CatalogDtos.ItemSummaryDto item,
            double positionSeconds,
            Double durationSeconds,
            Integer percentComplete) {}

    public record TranscodeSessionDto(
            String sessionId,
            String mediaItemId,
            String playlistUrl,
            double startSeconds,
            int height,
            String state) {}
}
