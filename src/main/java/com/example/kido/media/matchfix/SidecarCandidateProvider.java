package com.example.kido.media.matchfix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaFiles;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MetadataSource;
import com.example.kido.media.metadata.FilenameParser;
import com.example.kido.media.metadata.NfoParser;
import com.example.kido.media.metadata.SidecarMetadata;

import lombok.extern.slf4j.Slf4j;

/**
 * Builds correction candidates from what is actually available locally.
 *
 * <p>With no online database configured there is no catalogue to search, so the useful
 * sources are the ones the disk already provides, in rough order of how often each one
 * turns out to be right:
 *
 * <ol>
 *   <li>Every {@code .nfo} in the item's folder — including ones the scanner did not
 *       attach, which is the common case when a folder holds two films and the wrong
 *       sidecar won.</li>
 *   <li>The folder name. People name folders carefully ({@code Inception (2010)}) and
 *       files carelessly, so the folder is often the better evidence of the two.</li>
 *   <li>A re-reading of the filename, and the raw filename itself, for when the parser
 *       cut the title in the wrong place.</li>
 *   <li>Titles already correct elsewhere in the library, which catches a misfiled copy
 *       of something already indexed properly.</li>
 *   <li>Whatever the owner typed, always offered verbatim — the screen must never trap
 *       someone who knows the answer behind a list that does not contain it.</li>
 * </ol>
 */
@Slf4j
@Component
public class SidecarCandidateProvider implements MetadataCandidateProvider {

    /** How many already-indexed titles to consider before ranking. */
    private static final int LIBRARY_SCAN_LIMIT = 500;

    private final MediaPaths paths;
    private final NfoParser nfoParser;
    private final FilenameParser filenames;
    private final MediaItemRepository items;

    public SidecarCandidateProvider(MediaPaths paths,
                                    NfoParser nfoParser,
                                    FilenameParser filenames,
                                    MediaItemRepository items) {
        this.paths = paths;
        this.nfoParser = nfoParser;
        this.filenames = filenames;
        this.items = items;
    }

    @Override
    public String sourceName() {
        return "disk";
    }

    @Override
    public List<MatchCandidate> candidatesFor(MediaItem item, String query, int limit) {
        List<MatchCandidate> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int counter = 0;

        // 1. Sidecars in the item's own folder.
        for (SidecarMetadata meta : nearbySidecars(item)) {
            if (meta.title() == null || meta.title().isBlank()) {
                continue;
            }
            if (!seen.add(dedupeKey(meta.title(), meta.year()))) {
                continue;
            }
            candidates.add(MatchCandidate.builder()
                    .id("c" + counter++)
                    .title(meta.title())
                    .year(meta.year())
                    .plot(meta.plot())
                    .runtimeMinutes(meta.runtimeMinutes())
                    .rating(meta.rating())
                    .certification(meta.certification())
                    .studio(meta.studio())
                    .directors(join(meta.directors()))
                    .castMembers(join(meta.cast()))
                    .tmdbId(meta.tmdbId())
                    .imdbId(meta.imdbId())
                    .genres(meta.genres() == null ? Set.of() : meta.genres())
                    .origin("sidecar")
                    .reason("From a .nfo file in the same folder")
                    .build());
        }

        // 2. The folder name, often more carefully written than the filename.
        Optional<String> folderName = folderName(item);
        if (folderName.isPresent()) {
            FilenameParser.Parsed parsed = filenames.parse(folderName.get());
            if (seen.add(dedupeKey(parsed.title(), parsed.year()))) {
                candidates.add(MatchCandidate.builder()
                        .id("c" + counter++)
                        .title(parsed.title())
                        .year(parsed.year())
                        .origin("folder")
                        .reason("Read from the folder name, which is often more reliable "
                                + "than the filename")
                        .build());
            }
        }

        // 3. Re-readings of the filename.
        String base = MediaFiles.baseName(item.getFileName());
        FilenameParser.Parsed reparsed = filenames.parse(base);
        if (seen.add(dedupeKey(reparsed.title(), reparsed.year()))) {
            candidates.add(MatchCandidate.builder()
                    .id("c" + counter++)
                    .title(reparsed.title())
                    .year(reparsed.year())
                    .origin("filename")
                    .reason("The current guess, read from the filename")
                    .build());
        }
        String tidiedRaw = TitleSimilarity.normalise(base);
        if (!tidiedRaw.isBlank() && seen.add(dedupeKey(tidiedRaw, null))) {
            candidates.add(MatchCandidate.builder()
                    .id("c" + counter++)
                    .title(capitalise(tidiedRaw))
                    .origin("filename")
                    .reason("The whole filename, tidied up, in case the title was cut short")
                    .build());
        }

        // 4. Titles already indexed correctly elsewhere.
        for (MediaItem other : items.findByMetadataSourceInAndMissingFalseAndHiddenFalse(
                List.of(MetadataSource.NFO, MetadataSource.MANUAL),
                PageRequest.of(0, LIBRARY_SCAN_LIMIT))) {
            if (other.getId().equals(item.getId()) || other.getTitle() == null) {
                continue;
            }
            if (!seen.add(dedupeKey(other.getTitle(), other.getYear()))) {
                continue;
            }
            candidates.add(MatchCandidate.builder()
                    .id("c" + counter++)
                    .title(other.getTitle())
                    .year(other.getYear())
                    .plot(other.getPlot())
                    .runtimeMinutes(other.getRuntimeMinutes())
                    .rating(other.getRating())
                    .certification(other.getCertification())
                    .studio(other.getStudio())
                    .directors(other.getDirectors())
                    .castMembers(other.getCastMembers())
                    .tmdbId(other.getTmdbId())
                    .imdbId(other.getImdbId())
                    .genres(other.getGenres() == null ? Set.of() : Set.copyOf(other.getGenres()))
                    .origin("library")
                    .reason("Already in the library with confirmed metadata")
                    .build());
        }

        // 5. Whatever was typed, verbatim and always last so ranking can float it up.
        if (query != null && !query.isBlank()) {
            FilenameParser.Parsed typed = filenames.parse(query.trim());
            String typedTitle = typed.year() != null ? typed.title() : query.trim();
            if (seen.add(dedupeKey(typedTitle, typed.year()))) {
                candidates.add(MatchCandidate.builder()
                        .id("c" + counter++)
                        .title(typedTitle)
                        .year(typed.year())
                        .origin("typed")
                        .reason("Exactly what you typed")
                        .build());
            }
        }

        return candidates;
    }

    /**
     * Parses every {@code .nfo} beside the item, not just the one the scanner chose.
     *
     * <p>Deliberately re-reads the disk: the whole premise of this screen is that the
     * scanner's stored answer is wrong, so trusting its earlier reading would defeat it.
     */
    private List<SidecarMetadata> nearbySidecars(MediaItem item) {
        List<SidecarMetadata> parsed = new ArrayList<>();
        Path folder = resolveFolder(item).orElse(null);
        if (folder == null) {
            return parsed;
        }
        try (Stream<Path> entries = Files.list(folder)) {
            entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".nfo"))
                    .limit(20)
                    .forEach(path -> nfoParser.parse(path).ifPresent(parsed::add));
        } catch (IOException ex) {
            log.debug("Could not list sidecars beside {}: {}", item.getId(), ex.getMessage());
        }
        return parsed;
    }

    private Optional<Path> resolveFolder(MediaItem item) {
        if (!paths.isConfigured() || item.getFolderPath() == null) {
            return Optional.empty();
        }
        try {
            // Validated like any other path: this screen is owner-only, but the folder
            // still comes from a stored row and must be confirmed inside a library.
            return Optional.of(paths.requireWithinRoots(item.getFolderPath()));
        } catch (ApiException ex) {
            log.debug("Folder for {} is unavailable: {}", item.getId(), ex.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<String> folderName(MediaItem item) {
        if (item.getFolderPath() == null || item.getFolderPath().isBlank()) {
            return Optional.empty();
        }
        Path name = Path.of(item.getFolderPath()).getFileName();
        return name == null ? Optional.empty() : Optional.of(name.toString());
    }

    /** Title plus year, normalised, so the same suggestion is not offered twice. */
    private static String dedupeKey(String title, Integer year) {
        return TitleSimilarity.normalise(title) + "|" + (year == null ? "" : year);
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(", ", values);
    }

    /** Title-cases a normalised string so a tidied filename reads as a title. */
    private static String capitalise(String normalised) {
        StringBuilder out = new StringBuilder(normalised.length());
        boolean atWordStart = true;
        for (char character : normalised.toCharArray()) {
            out.append(atWordStart ? Character.toUpperCase(character) : character);
            atWordStart = character == ' ';
        }
        return out.toString();
    }
}
