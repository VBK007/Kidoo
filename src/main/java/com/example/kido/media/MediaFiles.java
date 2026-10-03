package com.example.kido.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.kido.media.catalog.MediaType;

/**
 * Which files on disk are library material, and what to tell the client they are.
 *
 * <p>Extension-based by design. Sniffing content would mean opening every file on a
 * multi-terabyte disk during a scan, and the extension is what the person organising
 * the disk actually controls.
 */
public final class MediaFiles {

    private MediaFiles() {}

    public static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "webm",
            "ts", "m2ts", "mts", "mpg", "mpeg", "divx", "vob", "3gp");

    public static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "alac", "aiff");

    /**
     * Everything a player can open, for asking "is this image somebody's cover?".
     */
    private static final Set<String> PLAYABLE_EXTENSIONS =
            Stream.concat(VIDEO_EXTENSIONS.stream(), AUDIO_EXTENSIONS.stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    public static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "avif", "heic", "heif", "gif", "bmp", "tif", "tiff", "dng");

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            // video
            Map.entry("mkv", "video/x-matroska"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("m4v", "video/x-m4v"),
            Map.entry("avi", "video/x-msvideo"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("wmv", "video/x-ms-wmv"),
            Map.entry("flv", "video/x-flv"),
            Map.entry("webm", "video/webm"),
            Map.entry("ts", "video/mp2t"),
            Map.entry("m2ts", "video/mp2t"),
            Map.entry("mts", "video/mp2t"),
            Map.entry("mpg", "video/mpeg"),
            Map.entry("mpeg", "video/mpeg"),
            Map.entry("vob", "video/dvd"),
            Map.entry("divx", "video/x-msvideo"),
            Map.entry("3gp", "video/3gpp"),
            // audio
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("flac", "audio/flac"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("aac", "audio/aac"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("oga", "audio/ogg"),
            Map.entry("opus", "audio/opus"),
            Map.entry("wav", "audio/wav"),
            Map.entry("wma", "audio/x-ms-wma"),
            Map.entry("alac", "audio/mp4"),
            Map.entry("aiff", "audio/aiff"),
            // image
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("webp", "image/webp"),
            Map.entry("heic", "image/heic"),
            Map.entry("heif", "image/heif"),
            Map.entry("gif", "image/gif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("tif", "image/tiff"),
            Map.entry("tiff", "image/tiff"),
            Map.entry("dng", "image/x-adobe-dng"));

    /** Names that are extras rather than the feature, whatever their size. */
    private static final Set<String> EXCLUDED_NAME_PARTS = Set.of(
            "sample", "trailer", "-featurette", "behind the scenes", "deleted scene");

    /**
     * Artwork and other sidecar images that must never become library entries in their
     * own right, even inside a photo library.
     */
    private static final Set<String> ARTWORK_BASENAMES = Set.of(
            "poster", "folder", "cover", "fanart", "backdrop", "background",
            "art", "banner", "logo", "thumb", "clearart", "disc", "landscape");

    /**
     * Suffixes an analysis tool appends to a track's own name for a derivative image —
     * {@code "Title_spectrogram.png"}, {@code "Title-waveform.png"} — that would
     * otherwise dodge the exact-basename check just below: it depicts the audio, not
     * anything a person would call a photo, but its name is not quite the track's own.
     */
    private static final Set<String> DERIVATIVE_IMAGE_SUFFIXES = Set.of("spectrogram", "waveform");

    /**
     * Same floor {@code SidecarLocator}'s last-resort poster match uses: below this, a
     * filename resemblance is more likely coincidence than a real cover.
     */
    private static final double LOOSE_NAME_MATCH_THRESHOLD = 0.5;
    private static final int LOOSE_NAME_MATCH_MIN_KEY_LENGTH = 5;

    public static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? ""
                : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    public static boolean isVideo(Path path) {
        return VIDEO_EXTENSIONS.contains(extension(path.getFileName().toString()));
    }

    public static boolean isAudio(Path path) {
        return AUDIO_EXTENSIONS.contains(extension(path.getFileName().toString()));
    }

    public static boolean isImage(Path path) {
        return IMAGE_EXTENSIONS.contains(extension(path.getFileName().toString()));
    }

    /** Any file the scanner should consider at all. */
    public static boolean isMedia(Path path) {
        return isVideo(path) || isAudio(path) || isImage(path);
    }

    /**
     * The kind a file's extension implies, independent of which library it sits in.
     *
     * <p>Used to keep a stray MP3 in a film library from being indexed as a film.
     */
    public static Optional<MediaType.Kind> kindOf(Path path) {
        if (isVideo(path)) {
            return Optional.of(MediaType.Kind.VIDEO);
        }
        if (isAudio(path)) {
            return Optional.of(MediaType.Kind.AUDIO);
        }
        if (isImage(path)) {
            return Optional.of(MediaType.Kind.IMAGE);
        }
        return Optional.empty();
    }

    /** Trailers and sample clips sit beside the feature and are not library entries. */
    public static boolean looksLikeExtra(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return EXCLUDED_NAME_PARTS.stream().anyMatch(lower::contains);
    }

    /**
     * True for {@code poster.jpg}, {@code fanart.png} and friends.
     *
     * <p>Matters only for image libraries: a photo scan walking a film folder would
     * otherwise index every piece of cover art as a photograph.
     */
    public static boolean isArtworkImage(String fileName) {
        if (!IMAGE_EXTENSIONS.contains(extension(fileName))) {
            return false;
        }
        String base = baseName(fileName).toLowerCase(Locale.ROOT);
        if (ARTWORK_BASENAMES.contains(base)) {
            return true;
        }
        // Also "Inception-poster.jpg" / "Inception.fanart.jpg".
        if (ARTWORK_BASENAMES.stream()
                .anyMatch(name -> base.endsWith("-" + name) || base.endsWith("." + name))) {
            return true;
        }
        // "AlbumArt {F011C0FA-7E53-4299-AB73-81EBDEF0BD07} Small.jpg" — Windows Media
        // Player's thumbnail cache, written into music folders without being asked. The
        // GUID makes every one unique, so no fixed name could catch them, and each
        // folder quietly contributed two more entries to the library.
        return base.startsWith("albumart");
    }

    /**
     * True for an image that belongs to another file rather than standing on its own.
     *
     * <p>The name-only check above handles the conventions that announce themselves:
     * {@code poster.jpg}, {@code Inception-fanart.jpg}. It cannot handle the most
     * common one of all, which is artwork named exactly after what it depicts —
     * {@code Soorarai pottru.jpg} beside {@code Soorarai pottru.mp4}. That is what
     * Plex and Emby write, and with nothing in the name to separate it from a
     * photograph it became a second library entry carrying the film's own title,
     * turning up in search looking exactly like a duplicate of the film.
     *
     * <p>The disk settles what the name cannot: if something playable in the same
     * folder has the same name, this image is its cover. Asked file by file rather
     * than by listing the directory, so a photo library of ten thousand images does
     * not turn the scan quadratic.
     *
     * <p>A release download rarely keeps that exact-name convention, though — its cover
     * is more often a short, hand-typed name like {@code helena.jpg} beside {@code
     * Helena 2026 1080p Tagalog WEB-DL HEVC x265 BONE.mkv}. Falling back to the same
     * fuzzy match {@code SidecarLocator}'s last-resort poster lookup accepts catches
     * that case too — without it, a cover found and attached as the movie's poster by
     * that lookup was also, separately, indexed here as its own playable-looking photo.
     */
    public static boolean isArtworkImage(Path file) {
        String fileName = file.getFileName().toString();
        if (isArtworkImage(fileName)) {
            return true;
        }
        if (!IMAGE_EXTENSIONS.contains(extension(fileName))) {
            return false;
        }
        Path parent = file.getParent();
        if (parent == null) {
            return false;
        }
        String base = baseName(fileName);
        if (hasPlayableSibling(base, parent)) {
            return true;
        }
        String withoutDerivativeSuffix = stripDerivativeSuffix(base);
        if (withoutDerivativeSuffix != null && hasPlayableSibling(withoutDerivativeSuffix, parent)) {
            return true;
        }
        return hasLooseMatchingVideoSibling(base, parent);
    }

    private static boolean hasLooseMatchingVideoSibling(String imageBase, Path parent) {
        try (Stream<Path> entries = Files.list(parent)) {
            return entries.filter(Files::isRegularFile)
                    .filter(MediaFiles::isVideo)
                    .anyMatch(video -> looseNameMatch(baseName(video.getFileName().toString()), imageBase));
        } catch (IOException ex) {
            return false;
        }
    }

    /** Same scoring as {@code SidecarLocator.loosePrefixSimilarity}, duplicated rather
     * than shared so this package does not take on a dependency the other way round. */
    private static boolean looseNameMatch(String videoBase, String imageBase) {
        String videoKey = alnumKey(videoBase);
        String imageKey = alnumKey(imageBase);
        if (videoKey.isBlank() || imageKey.length() < LOOSE_NAME_MATCH_MIN_KEY_LENGTH) {
            return false;
        }
        int windowLength = Math.min(videoKey.length(), imageKey.length() + 2);
        String window = videoKey.substring(0, windowLength);
        double similarity = 1.0 - ((double) levenshtein(window, imageKey) / imageKey.length());
        return similarity >= LOOSE_NAME_MATCH_THRESHOLD;
    }

    private static String alnumKey(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Two-row Levenshtein; these keys are short, so the quadratic cost is irrelevant. */
    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static boolean hasPlayableSibling(String base, Path parent) {
        for (String extension : PLAYABLE_EXTENSIONS) {
            // Both cases because the check is a filesystem lookup: Windows does not
            // care, ext4 very much does, and "HOLIDAY.MP4" is a real way for a camera
            // to name a file.
            if (Files.exists(parent.resolve(base + "." + extension))
                    || Files.exists(parent.resolve(base + "." + extension.toUpperCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }

    /** {@code "Title_spectrogram"}/{@code "Title-waveform"} -> {@code "Title"}, or null
     * when the name carries none of {@link #DERIVATIVE_IMAGE_SUFFIXES}. */
    private static String stripDerivativeSuffix(String base) {
        String lower = base.toLowerCase(Locale.ROOT);
        for (String suffix : DERIVATIVE_IMAGE_SUFFIXES) {
            for (String separator : new String[] {"-", "_"}) {
                String marker = separator + suffix;
                if (lower.endsWith(marker)) {
                    return base.substring(0, base.length() - marker.length());
                }
            }
        }
        return null;
    }

    public static String contentType(String fileName) {
        return CONTENT_TYPES.getOrDefault(extension(fileName), "application/octet-stream");
    }
}
