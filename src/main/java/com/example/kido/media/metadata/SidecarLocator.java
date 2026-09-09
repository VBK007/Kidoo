package com.example.kido.media.metadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.example.kido.media.MediaFiles;

import lombok.extern.slf4j.Slf4j;

/**
 * Finds the files that sit beside a media file: its {@code .nfo}, its artwork and its
 * external subtitle tracks.
 *
 * <p>Naming is not standardised across Kodi, Plex, Radarr and Emby, so each lookup
 * walks an ordered list of candidates and takes the first hit. Ordering matters:
 * a movie-specific {@code Inception-poster.jpg} beats a generic {@code folder.jpg}
 * that may belong to the whole directory.
 */
@Slf4j
@Component
public class SidecarLocator {

    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp");

    /** Generic artwork names, tried after the movie-specific ones. */
    private static final List<String> POSTER_NAMES = List.of("poster", "folder", "cover", "movie", "default");
    private static final List<String> BACKDROP_NAMES = List.of("fanart", "backdrop", "background", "art");

    private static final Set<String> SUBTITLE_EXTENSIONS = Set.of("srt", "vtt", "ass", "ssa", "sub");

    /** Sub-directories that conventionally hold external subtitles. */
    private static final List<String> SUBTITLE_DIRS = List.of("Subs", "subs", "Subtitles", "subtitles");

    /**
     * Locates the metadata sidecar for {@code videoFile}.
     *
     * <p>{@code <basename>.nfo} wins over {@code movie.nfo} because a folder holding
     * several files needs the per-file one to be authoritative.
     */
    public Optional<Path> findNfo(Path videoFile) {
        Path folder = videoFile.getParent();
        if (folder == null) {
            return Optional.empty();
        }
        String base = MediaFiles.baseName(videoFile.getFileName().toString());
        return firstExisting(
                folder.resolve(base + ".nfo"),
                folder.resolve("movie.nfo"),
                folder.resolve(folder.getFileName() + ".nfo"));
    }

    public Optional<Path> findPoster(Path videoFile) {
        return findArtwork(videoFile, POSTER_NAMES);
    }

    public Optional<Path> findBackdrop(Path videoFile) {
        return findArtwork(videoFile, BACKDROP_NAMES);
    }

    private Optional<Path> findArtwork(Path videoFile, List<String> genericNames) {
        Path folder = videoFile.getParent();
        if (folder == null) {
            return Optional.empty();
        }
        String base = MediaFiles.baseName(videoFile.getFileName().toString());
        List<Path> candidates = new ArrayList<>();

        // Movie-specific first: "Inception-poster.jpg", "Inception.jpg".
        for (String name : genericNames) {
            for (String extension : IMAGE_EXTENSIONS) {
                candidates.add(folder.resolve(base + "-" + name + "." + extension));
            }
        }
        for (String extension : IMAGE_EXTENSIONS) {
            candidates.add(folder.resolve(base + "." + extension));
        }
        // Then folder-wide artwork.
        for (String name : genericNames) {
            for (String extension : IMAGE_EXTENSIONS) {
                candidates.add(folder.resolve(name + "." + extension));
            }
        }
        return firstExisting(candidates.toArray(Path[]::new));
    }

    /**
     * External subtitle tracks for {@code videoFile}, from its own folder and from a
     * conventional {@code Subs/} sub-directory.
     */
    public List<SubtitleTrack> findSubtitles(Path videoFile) {
        Path folder = videoFile.getParent();
        if (folder == null) {
            return List.of();
        }
        String base = MediaFiles.baseName(videoFile.getFileName().toString());
        List<SubtitleTrack> tracks = new ArrayList<>();

        collectSubtitles(folder, base, true, tracks);
        for (String dirName : SUBTITLE_DIRS) {
            Path subsDir = folder.resolve(dirName);
            if (Files.isDirectory(subsDir)) {
                // Inside a dedicated Subs/ folder the files are often named by language
                // alone ("English.srt"), so the basename prefix is not required there.
                collectSubtitles(subsDir, base, false, tracks);
            }
        }
        return tracks;
    }

    private void collectSubtitles(Path folder, String base, boolean requirePrefix, List<SubtitleTrack> out) {
        try (Stream<Path> entries = Files.list(folder)) {
            entries.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                String extension = MediaFiles.extension(name);
                if (!SUBTITLE_EXTENSIONS.contains(extension)) {
                    return;
                }
                if (requirePrefix && !name.regionMatches(true, 0, base, 0, base.length())) {
                    return;
                }
                out.add(new SubtitleTrack(
                        path.toAbsolutePath().normalize().toString(),
                        languageOf(MediaFiles.baseName(name), base),
                        extension,
                        name.toLowerCase(Locale.ROOT).contains("forced"),
                        name.toLowerCase(Locale.ROOT).contains("sdh")));
            });
        } catch (IOException ex) {
            log.debug("Could not list subtitles in {}: {}", folder, ex.getMessage());
        }
    }

    /**
     * Pulls a language label out of the part of the filename after the movie basename,
     * e.g. {@code Inception.en.forced.srt} to {@code en}. Falls back to the whole
     * remainder, or {@code und} when there is nothing left.
     */
    private static String languageOf(String subtitleBase, String movieBase) {
        String remainder = subtitleBase.regionMatches(true, 0, movieBase, 0, movieBase.length())
                ? subtitleBase.substring(movieBase.length())
                : subtitleBase;
        String[] parts = remainder.split("[.\\-_ ]+");
        for (String part : parts) {
            String token = part.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty() || token.equals("forced") || token.equals("sdh") || token.equals("cc")) {
                continue;
            }
            return token;
        }
        return "und";
    }

    private static Optional<Path> firstExisting(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    /**
     * @param path     absolute path, re-validated against the media roots before it is read
     * @param language BCP-47-ish tag or label taken from the filename
     * @param format   file extension, which decides whether conversion to WebVTT is needed
     */
    public record SubtitleTrack(String path, String language, String format, boolean forced, boolean hearingImpaired) {}
}
