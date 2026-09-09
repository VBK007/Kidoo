package com.example.kido.media;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for the home-server movie library ({@code app.media.*}).
 *
 * <p>Nothing here has a machine-specific default: with no {@code app.media.roots}
 * configured the module stays dormant (the catalog is simply empty) so the rest
 * of the app — and the test suite — runs unchanged on machines with no movie disk.
 */
@Component
@ConfigurationProperties(prefix = "app.media")
@Getter
@Setter
public class MediaProperties {

    /** Absolute directories to scan for movies, e.g. {@code D:/Movies,E:/Movies}. */
    private List<String> roots = new ArrayList<>();

    /** ffmpeg / ffprobe executables. Bare names resolve via PATH. */
    private String ffmpegPath = "ffmpeg";
    private String ffprobePath = "ffprobe";

    /**
     * Run ffprobe on each new file during a scan. Accurate but slow (a fraction of
     * a second per file); when disabled, files are probed lazily on first playback.
     */
    private boolean probeOnScan = true;

    /** Hard ceiling on a single ffprobe invocation, so one bad file cannot stall a scan. */
    private int probeTimeoutSeconds = 30;

    /** Kick off a library scan when the application starts. */
    private boolean scanOnStartup = false;

    /**
     * Files smaller than this are skipped as trailers/samples/extras.
     * Zero disables the check.
     */
    private long minFileSizeMb = 50;

    /** Scratch space for HLS segments. Wiped on startup and as sessions expire. */
    private String transcodeDir = System.getProperty("java.io.tmpdir") + "/kido-transcode";

    /** A transcode session with no segment requests for this long is killed. */
    private int transcodeIdleTimeoutSeconds = 120;

    /**
     * Concurrent ffmpeg transcodes allowed. Each one saturates several CPU cores,
     * so a home server should keep this small.
     */
    private int maxTranscodeSessions = 2;

    /** Constant Rate Factor for transcodes — lower is better quality and bigger. */
    private int transcodeCrf = 21;

    /** x264 preset; {@code veryfast} is the usual real-time compromise. */
    private String transcodePreset = "veryfast";

    /** Target segment length in seconds. */
    private int hlsSegmentSeconds = 6;
}
