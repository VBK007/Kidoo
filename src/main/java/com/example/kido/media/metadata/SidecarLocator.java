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
        Optional<Path> conventional = findArtwork(videoFile, POSTER_NAMES);
        return conventional.isPresent() ? conventional : findLooseMatch(videoFile);
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

    /** Below this, a "match" is more likely coincidence than a real thumbnail. */
    private static final double LOOSE_MATCH_THRESHOLD = 0.5;

    /** A slug too short to be specific to one title (e.g. "img", "a"). */
    private static final int LOOSE_MATCH_MIN_KEY_LENGTH = 5;

    /**
     * Last-resort poster lookup for a folder that holds several videos and images with
     * no naming convention linking them — a quickly-made thumbnail is often just the
     * title with the spaces dropped ({@code Anbe Diana} to {@code anabediana.jpg}), which
     * matches nothing {@link #findArtwork} looks for.
     *
     * <p>Scored against every image directly in the folder (not recursively, same as the
     * conventional lookup) rather than paired up front, so a wrong guess for one video
     * cannot cost its neighbour the right image — each video independently finds its own
     * best-scoring image, and unrelated filenames score too low to be picked at all.
     */
    private Optional<Path> findLooseMatch(Path videoFile) {
        Path folder = videoFile.getParent();
        if (folder == null) {
            return Optional.empty();
        }
        String videoKey = alnumKey(MediaFiles.baseName(videoFile.getFileName().toString()));
        if (videoKey.isBlank()) {
            return Optional.empty();
        }

        Path best = null;
        double bestScore = 0;
        try (Stream<Path> entries = Files.list(folder)) {
            for (Path path : entries.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (!IMAGE_EXTENSIONS.contains(MediaFiles.extension(name))) {
                    continue;
                }
                String imageKey = alnumKey(MediaFiles.baseName(name));
                if (imageKey.length() < LOOSE_MATCH_MIN_KEY_LENGTH) {
                    continue;
                }
                double score = loosePrefixSimilarity(videoKey, imageKey);
                if (score > bestScore) {
                    bestScore = score;
                    best = path;
                }
            }
        } catch (IOException ex) {
            log.debug("Could not list images in {}: {}", folder, ex.getMessage());
        }

        if (best == null || bestScore < LOOSE_MATCH_THRESHOLD) {
            return Optional.empty();
        }
        log.debug("Loose poster match for {}: {} (score {})",
                videoFile.getFileName(), best.getFileName(), bestScore);
        return Optional.of(best.toAbsolutePath().normalize());
    }

    /** Lower-cased letters and digits only, so separators never count as a difference. */
    private static String alnumKey(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * How well {@code imageKey} matches the start of {@code videoKey}, as similarity in
     * {@code [0, 1]}.
     *
     * <p>Only the video key's own leading window — the image key's length plus a little
     * slack for a dropped or doubled letter — is compared, and edit distance is
     * normalised against the (short) image key rather than the (long, release-tag-laden)
     * video key. Comparing the full video key directly would drown a perfect match for
     * the title in the length of everything typed after it.
     */
    private static double loosePrefixSimilarity(String videoKey, String imageKey) {
        int windowLength = Math.min(videoKey.length(), imageKey.length() + 2);
        String window = videoKey.substring(0, windowLength);
        int distance = levenshtein(window, imageKey);
        return 1.0 - ((double) distance / imageKey.length());
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
