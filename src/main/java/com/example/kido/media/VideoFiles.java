package com.example.kido.media;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Which files on disk count as movies, and what to tell the client they are. */
public final class VideoFiles {

    private VideoFiles() {}

    public static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "webm",
            "ts", "m2ts", "mts", "mpg", "mpeg", "divx", "vob");

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
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
            Map.entry("divx", "video/x-msvideo"));

    /** Names that are extras rather than the feature, whatever their size. */
    private static final Set<String> EXCLUDED_NAME_PARTS = Set.of(
            "sample", "trailer", "-featurette", "behind the scenes", "deleted scene");

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

    /** Trailers and sample clips sit next to the feature and must not become library entries. */
    public static boolean looksLikeExtra(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return EXCLUDED_NAME_PARTS.stream().anyMatch(lower::contains);
    }

    public static String contentType(String fileName) {
        return CONTENT_TYPES.getOrDefault(extension(fileName), "application/octet-stream");
    }
}
