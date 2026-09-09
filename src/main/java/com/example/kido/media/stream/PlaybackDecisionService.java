package com.example.kido.media.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.dto.PlaybackDtos.ClientCapabilitiesRequest;

/**
 * Decides whether a client can play a file as-is or needs it re-encoded.
 *
 * <p>Direct play is worth real effort to reach: it costs the server almost no CPU,
 * preserves the original quality, and seeks instantly. Transcoding a 1080p file
 * saturates several cores, so on a home server it is the fallback, not the default.
 *
 * <p>The check is conservative in one specific direction. Every unknown — an unprobed
 * file, a capability the client did not declare — resolves toward transcoding, because
 * a wrong guess toward transcode wastes CPU while a wrong guess toward direct play
 * shows the user a black screen or silent audio.
 */
@Service
public class PlaybackDecisionService {

    /**
     * Containers that stream over plain HTTP range requests on essentially every client.
     * Matroska is excluded even though ExoPlayer handles it, because iOS does not and
     * progressive MKV playback depends heavily on how the file was muxed.
     */
    private static final Set<String> UNIVERSAL_CONTAINERS = Set.of("mp4", "m4v", "mov");

    /**
     * ffprobe reports {@code format_name} as a comma-separated list of everything the
     * demuxer matched, e.g. {@code mov,mp4,m4a,3gp,3g2,mj2}.
     */
    private static Set<String> containerAliases(String formatName) {
        if (formatName == null || formatName.isBlank()) {
            return Set.of();
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : formatName.toLowerCase(Locale.ROOT).split(",")) {
            String token = part.trim();
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    /**
     * @param item an item whose {@link MediaInfo} has ideally been probed
     * @return the decision plus the human-readable reasons behind it
     */
    public Decision decide(MediaItem item, ClientCapabilitiesRequest caps) {
        List<String> reasons = new ArrayList<>();
        MediaInfo info = item.getMediaInfo();

        if (info == null || !info.isProbed()) {
            // Without a probe there is nothing to compare against. Transcoding produces
            // a known-good H.264/AAC stream, so it is the safe answer.
            reasons.add("File has not been probed; cannot verify client compatibility");
            return new Decision(false, reasons, targetHeight(caps, null));
        }

        boolean compatible = true;

        Set<String> fileContainers = containerAliases(info.getContainer());
        List<String> clientContainers = caps.containersOrEmpty().stream()
                .map(c -> c.toLowerCase(Locale.ROOT))
                .toList();
        boolean containerOk = clientContainers.isEmpty()
                ? fileContainers.stream().anyMatch(UNIVERSAL_CONTAINERS::contains)
                : fileContainers.stream().anyMatch(clientContainers::contains);
        if (!containerOk) {
            compatible = false;
            reasons.add("Container " + orUnknown(info.getContainer()) + " is not directly playable");
        }

        String videoCodec = normalise(info.getVideoCodec());
        if (videoCodec == null) {
            compatible = false;
            reasons.add("Video codec unknown");
        } else if (!supports(caps.videoCodecsOrEmpty(), videoCodec, "h264")) {
            compatible = false;
            reasons.add("Video codec " + videoCodec + " not supported by client");
        }

        String audioCodec = normalise(info.primaryAudioCodec());
        if (audioCodec == null) {
            // A silent file is unusual but playable; do not force a transcode for it.
            reasons.add("No audio stream detected");
        } else if (!supports(caps.audioCodecsOrEmpty(), audioCodec, "aac")) {
            compatible = false;
            reasons.add("Audio codec " + audioCodec + " not supported by client");
        }

        if (caps.maxHeight() != null && info.getHeight() != null
                && info.getHeight() > caps.maxHeight()) {
            compatible = false;
            reasons.add("Height " + info.getHeight() + "p exceeds client maximum of "
                    + caps.maxHeight() + "p");
        }

        if (caps.maxBitrate() != null && info.getBitrate() != null
                && info.getBitrate() > caps.maxBitrate()) {
            compatible = false;
            reasons.add("Bitrate " + info.getBitrate() + " bps exceeds client limit of "
                    + caps.maxBitrate() + " bps");
        }

        if (compatible) {
            reasons.add("Direct play: container and codecs match client capabilities");
            return new Decision(true, reasons, null);
        }

        if (!caps.hlsAllowed()) {
            // Nothing left to offer: the client rejected HLS and cannot take the original.
            reasons.add("Client does not support HLS, so no transcode path is available");
        }
        return new Decision(false, reasons, targetHeight(caps, info));
    }

    /**
     * Height to transcode to: the client ceiling, never upscaled above the source.
     * Falls back to 720p, which every phone decodes and a home upload can sustain.
     */
    private static int targetHeight(ClientCapabilitiesRequest caps, MediaInfo info) {
        int requested = caps.maxHeight() == null ? 720 : caps.maxHeight();
        if (info != null && info.getHeight() != null && info.getHeight() > 0) {
            return Math.min(requested, info.getHeight());
        }
        return requested;
    }

    /**
     * A client that declared nothing is credited only with the universal baseline
     * ({@code h264}/{@code aac}) rather than assumed to support whatever the file holds.
     */
    private static boolean supports(List<String> declared, String codec, String baseline) {
        if (declared.isEmpty()) {
            return baseline.equals(codec);
        }
        return declared.stream().anyMatch(c -> normalise(c).equals(codec));
    }

    /** Folds the common aliases so {@code avc1}, {@code h.264} and {@code h264} compare equal. */
    private static String normalise(String codec) {
        if (codec == null || codec.isBlank()) {
            return null;
        }
        String lower = codec.toLowerCase(Locale.ROOT).trim().replace(".", "").replace("-", "");
        return switch (lower) {
            case "avc", "avc1", "h264" -> "h264";
            case "hevc", "hvc1", "hev1", "h265" -> "hevc";
            case "mp4a", "aac", "aaclc" -> "aac";
            case "ac3" -> "ac3";
            case "eac3", "ec3" -> "eac3";
            case "vp09", "vp9" -> "vp9";
            case "av01", "av1" -> "av1";
            default -> lower;
        };
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /**
     * @param directPlay   true when the original bytes can be served as-is
     * @param reasons      why, in client-readable form — useful in the app's debug view
     * @param targetHeight height to transcode to, null when direct playing
     */
    public record Decision(boolean directPlay, List<String> reasons, Integer targetHeight) {}
}
