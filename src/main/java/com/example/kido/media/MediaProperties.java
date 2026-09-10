package com.example.kido.media;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.example.kido.media.catalog.MediaType;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for the home media server ({@code app.media.*}).
 *
 * <p>Nothing here has a machine-specific default: with no libraries configured the
 * module stays dormant (the catalog is simply empty) so the rest of the app — and the
 * test suite — runs unchanged on machines with no media disk.
 */
@Component
@ConfigurationProperties(prefix = "app.media")
@Getter
@Setter
public class MediaProperties {

    /**
     * Typed libraries, e.g.
     * <pre>
     * app.media.libraries[0].name=Films
     * app.media.libraries[0].path=D:/Movies
     * app.media.libraries[0].type=FILM
     * </pre>
     *
     * <p>A type per root rather than per file: nothing in a filename reliably separates
     * a film from an anime, but the person who organised the disk already did.
     */
    private List<Library> libraries = new ArrayList<>();

    /**
     * Untyped shorthand for the single-folder case; each entry becomes a {@link
     * MediaType#FILM} library. Ignored when {@link #libraries} is set.
     */
    private List<String> roots = new ArrayList<>();

    /** ffmpeg / ffprobe executables. Bare names resolve via PATH. */
    private String ffmpegPath = "ffmpeg";
    private String ffprobePath = "ffprobe";

    /**
     * Run ffprobe on each new video during a scan. Accurate but slow (a process per
     * file); when disabled, files are probed lazily on first playback.
     */
    private boolean probeOnScan = true;

    /** Hard ceiling on one ffprobe invocation, so a corrupt file cannot stall a scan. */
    private int probeTimeoutSeconds = 30;

    /** Kick off a library scan when the application starts. */
    private boolean scanOnStartup = false;

    /**
     * Minutes between automatic background scans that pick up files dropped into a
     * library without anyone triggering a scan by hand. Zero (the default) disables
     * periodic scanning — {@link #scanOnStartup} and the admin {@code POST .../scan}
     * endpoint remain the only ways to index new media.
     */
    private int scanIntervalMinutes = 0;

    /**
     * Videos smaller than this are skipped as trailers/samples/extras. Applies to video
     * only — photos and songs are legitimately small. Zero disables the check.
     */
    private long minFileSizeMb = 50;

    /** Scratch space for HLS segments. Wiped on startup and as sessions expire. */
    private String transcodeDir = System.getProperty("java.io.tmpdir") + "/kido-transcode";

    /** A transcode session with no segment requests for this long is killed. */
    private int transcodeIdleTimeoutSeconds = 120;

    /**
     * Concurrent ffmpeg transcodes allowed. Each saturates several cores, so a home
     * server should keep this small.
     */
    private int maxTranscodeSessions = 2;

    /** Constant Rate Factor for transcodes — lower is better quality and bigger. */
    private int transcodeCrf = 21;

    /** x264 preset; {@code veryfast} is the usual real-time compromise. */
    private String transcodePreset = "veryfast";

    /** Target HLS segment length in seconds. */
    private int hlsSegmentSeconds = 6;

    @Getter
    @Setter
    private Trickplay trickplay = new Trickplay();

    @Getter
    @Setter
    private Downloads downloads = new Downloads();

    /**
     * Offline copies prepared for a device to take away.
     *
     * <p>Distinct settings from live transcoding on purpose. A download is not played
     * as it is produced, so it can afford a slower preset for a materially smaller
     * file — which is the whole point when the destination is a phone with finite
     * storage on a finite data plan.
     */
    @Getter
    @Setter
    public static class Downloads {

        private boolean enabled = true;

        /** Where prepared copies are written. Never inside a media library. */
        private String dir = System.getProperty("java.io.tmpdir") + "/kido-downloads";

        /**
         * A prepared copy is deleted this long after it was made. The client is
         * expected to fetch it promptly; the file is only a staging artifact.
         */
        private int retentionHours = 72;

        /** Jobs a single profile may have outstanding, to stop one device hogging the queue. */
        private int maxQueuedPerProfile = 5;

        /**
         * Slower than the live-transcode preset and worth it: nothing is waiting on
         * this frame-by-frame, and a smaller file is the entire deliverable.
         */
        private String preset = "medium";

        /** Slightly higher CRF than live playback, since the target is a phone screen. */
        private int crf = 23;

        /** A long film on a slow preset legitimately takes a while. */
        private int timeoutMinutes = 240;

        /** Default vertical resolution when the client does not ask for one. */
        private int defaultHeight = 720;
    }

    /** One configured library root. */
    @Getter
    @Setter
    public static class Library {

        /** Display name, defaulted from the type when omitted. */
        private String name;

        /** Absolute directory to index. */
        private String path;

        private MediaType type = MediaType.FILM;
    }

    /**
     * Thumbnail-scrubbing frames.
     *
     * <p>Generating these decodes the whole file, so it is off by default and run as an
     * explicit background job rather than during a scan.
     */
    @Getter
    @Setter
    public static class Trickplay {

        /** Generate sprite sheets automatically after a scan indexes a new video. */
        private boolean enabled = false;

        /** Seconds between captured frames. Smaller means finer scrubbing and more disk. */
        private int intervalSeconds = 10;

        /** Width of each captured frame in pixels; height follows the aspect ratio. */
        private int tileWidth = 320;

        /** Frames per sprite sheet, as {@code columns} x {@code rows}. */
        private int columns = 10;
        private int rows = 10;

        /** JPEG quality passed to ffmpeg as {@code -q:v} (2 best, 31 worst). */
        private int quality = 5;

        /** Where sprite sheets are cached. Survives restarts; cleared per item on demand. */
        private String cacheDir = System.getProperty("java.io.tmpdir") + "/kido-trickplay";

        /** Ceiling on one generation job, since a long film decodes for minutes. */
        private int timeoutMinutes = 30;

        public int framesPerSheet() {
            return Math.max(1, columns) * Math.max(1, rows);
        }
    }

    /**
     * The configured libraries, with {@link #roots} folded in as FILM libraries.
     *
     * <p>Resolved here rather than at each call site so the two config styles collapse
     * into one list before anything else sees them.
     */
    public List<Library> effectiveLibraries() {
        List<Library> resolved = new ArrayList<>();
        for (Library library : libraries) {
            if (library != null && library.getPath() != null && !library.getPath().isBlank()) {
                if (library.getName() == null || library.getName().isBlank()) {
                    library.setName(library.getType().label());
                }
                resolved.add(library);
            }
        }
        if (resolved.isEmpty()) {
            for (String root : roots) {
                if (root == null || root.isBlank()) {
                    continue;
                }
                Library library = new Library();
                library.setPath(root.trim());
                library.setType(MediaType.FILM);
                library.setName(MediaType.FILM.label());
                resolved.add(library);
            }
        }
        return resolved;
    }
}
