package com.example.kido.media.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * What ffprobe found inside the container. Cached on the {@link Movie} row because
 * probing costs a process spawn per file, and the playback decision needs it on
 * every single play request.
 */
@Embeddable
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaInfo {

    @Column(name = "probe_container")
    private String container;

    @Column(name = "probe_duration_seconds")
    private Double durationSeconds;

    @Column(name = "probe_video_codec")
    private String videoCodec;

    @Column(name = "probe_width")
    private Integer width;

    @Column(name = "probe_height")
    private Integer height;

    /** Whole-file bitrate in bits per second — the number that decides if a direct play will stall. */
    @Column(name = "probe_bitrate")
    private Long bitrate;

    @Column(name = "probe_video_profile")
    private String videoProfile;

    /** Comma-separated, in stream order, e.g. {@code eac3,aac}. */
    @Column(name = "probe_audio_codecs")
    private String audioCodecs;

    @Column(name = "probe_audio_channels")
    private Integer audioChannels;

    /**
     * Audio tracks as {@code index:codec:language:title} entries, so the player's audio
     * chip can offer "English 5.1" / "Hindi" rather than just a count.
     */
    @Column(name = "probe_audio_tracks", length = 2000)
    private String audioTracks;

    /** Embedded subtitle tracks, as {@code index:codec:language} entries. */
    @Column(name = "probe_subtitles", length = 2000)
    private String embeddedSubtitles;

    @Column(name = "probed_at")
    private Instant probedAt;

    /** Null-safe: an unprobed row has no {@code probedAt}. */
    public boolean isProbed() {
        return probedAt != null;
    }

    public String primaryAudioCodec() {
        if (audioCodecs == null || audioCodecs.isBlank()) return null;
        int comma = audioCodecs.indexOf(',');
        return comma < 0 ? audioCodecs : audioCodecs.substring(0, comma);
    }
}
