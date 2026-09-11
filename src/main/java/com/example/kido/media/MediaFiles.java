package com.example.kido.media;

import java.nio.file.Path;
import java.util.Locale;
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
        return ARTWORK_BASENAMES.stream()
                .anyMatch(name -> base.endsWith("-" + name) || base.endsWith("." + name));
    }

    public static String contentType(String fileName) {
        return CONTENT_TYPES.getOrDefault(extension(fileName), "application/octet-stream");
    }
}
