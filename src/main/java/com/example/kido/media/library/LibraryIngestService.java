package com.example.kido.media.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.MediaProperties;
import com.example.kido.media.VideoFiles;
import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MetadataSource;
import com.example.kido.media.catalog.Movie;
import com.example.kido.media.catalog.MovieRepository;
import com.example.kido.media.metadata.FilenameParser;
import com.example.kido.media.metadata.NfoParser;
import com.example.kido.media.metadata.SidecarLocator;
import com.example.kido.media.metadata.SidecarMetadata;
import com.example.kido.media.probe.MediaProbe;

import lombok.extern.slf4j.Slf4j;

/**
 * Reconciles a single file on disk with its {@code movies} row.
 *
 * <p>Deliberately a separate bean from {@link LibraryScanner}: {@code @Transactional}
 * is applied by a proxy, so a scanner calling its own annotated method would silently
 * run with no transaction at all. Each file is committed on its own, which also means a
 * scan that dies halfway leaves everything it already indexed intact.
 */
@Slf4j
@Service
public class LibraryIngestService {

    private final MediaProperties props;
    private final MovieRepository movies;
    private final NfoParser nfoParser;
    private final SidecarLocator sidecars;
    private final FilenameParser filenames;
    private final MediaProbe probe;

    public LibraryIngestService(MediaProperties props,
                                MovieRepository movies,
                                NfoParser nfoParser,
                                SidecarLocator sidecars,
                                FilenameParser filenames,
                                MediaProbe probe) {
        this.props = props;
        this.movies = movies;
        this.nfoParser = nfoParser;
        this.sidecars = sidecars;
        this.filenames = filenames;
        this.probe = probe;
    }

    /** What {@link #ingest} did with a file, so the scanner can keep its counters. */
    public enum Outcome {
        ADDED, UPDATED, UNCHANGED, SKIPPED
    }

    @Transactional
    public Outcome ingest(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        if (VideoFiles.looksLikeExtra(fileName)) {
            return Outcome.SKIPPED;
        }
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        long minBytes = props.getMinFileSizeMb() * 1024L * 1024L;
        if (minBytes > 0 && attrs.size() < minBytes) {
            log.debug("Skipping {} ({} bytes, below configured minimum)", fileName, attrs.size());
            return Outcome.SKIPPED;
        }

        String absolutePath = file.toAbsolutePath().normalize().toString();
        Instant modifiedAt = attrs.lastModifiedTime().toInstant();
        Optional<Movie> existing = movies.findByFilePath(absolutePath);

        if (existing.isPresent()) {
            Movie movie = existing.get();
            boolean contentUnchanged = movie.getFileSize() == attrs.size()
                    && modifiedAt.equals(movie.getFileModifiedAt());

            // The cheap path that makes rescanning a large library viable.
            if (contentUnchanged && movie.getMetadataSource() != null && !movie.isMissing()) {
                return Outcome.UNCHANGED;
            }

            applyMetadata(movie, file, attrs.size(), modifiedAt);
            if (!contentUnchanged) {
                // The bytes changed, so the cached probe describes a file that no longer exists.
                movie.setMediaInfo(new MediaInfo());
            }
            probeIfNeeded(movie, file);
            movie.setMissing(false);
            movie.setUpdatedAt(Instant.now());
            movies.save(movie);
            return Outcome.UPDATED;
        }

        Movie movie = Movie.builder()
                .filePath(absolutePath)
                .fileName(fileName)
                .title(fileName)
                .build();
        applyMetadata(movie, file, attrs.size(), modifiedAt);
        probeIfNeeded(movie, file);
        movies.save(movie);
        return Outcome.ADDED;
    }

    /**
     * Flags rows whose files this scan did not encounter.
     *
     * <p>Rows are kept rather than deleted so watch progress survives a disk being
     * unmounted or a folder renamed; the catalog filters missing rows out instead.
     *
     * @return how many rows were newly marked missing
     */
    @Transactional
    public int markMissing(Set<String> seenPaths) {
        Set<String> seen = new HashSet<>(seenPaths);
        List<Movie> gone = new ArrayList<>();
        for (Movie movie : movies.findByMissingFalse()) {
            if (!seen.contains(movie.getFilePath())) {
                movie.setMissing(true);
                movie.setUpdatedAt(Instant.now());
                gone.add(movie);
            }
        }
        if (!gone.isEmpty()) {
            movies.saveAll(gone);
            log.info("Marked {} movies as missing", gone.size());
        }
        return gone.size();
    }

    /** Probes on demand for a row indexed while {@code probe-on-scan} was off. */
    @Transactional
    public Movie ensureProbed(Movie movie, Path file) {
        if (movie.getMediaInfo() != null && movie.getMediaInfo().isProbed()) {
            return movie;
        }
        Optional<MediaInfo> probed = probe.probe(file);
        if (probed.isEmpty()) {
            return movie;
        }
        movie.setMediaInfo(probed.get());
        movie.setUpdatedAt(Instant.now());
        return movies.save(movie);
    }

    /** Fills descriptive fields from the sidecar when there is one, else from the filename. */
    private void applyMetadata(Movie movie, Path file, long size, Instant modifiedAt) {
        String fileName = file.getFileName().toString();
        Path folder = file.getParent();

        movie.setFileName(fileName);
        movie.setFileSize(size);
        movie.setFileModifiedAt(modifiedAt);
        movie.setFolderPath(folder == null ? null : folder.toAbsolutePath().normalize().toString());

        FilenameParser.Parsed parsed = filenames.parse(VideoFiles.baseName(fileName));
        movie.setQuality(parsed.quality());

        Optional<SidecarMetadata> sidecar = sidecars.findNfo(file).flatMap(nfoParser::parse);

        if (sidecar.isPresent()) {
            SidecarMetadata meta = sidecar.get();
            movie.setMetadataSource(MetadataSource.NFO);
            // The sidecar wins, but field by field: partial .nfo files are common and
            // must not blank out what the filename could still supply.
            movie.setTitle(orElse(meta.title(), parsed.title()));
            movie.setOriginalTitle(meta.originalTitle());
            movie.setYear(meta.year() != null ? meta.year() : parsed.year());
            movie.setPlot(meta.plot());
            movie.setTagline(meta.tagline());
            movie.setRuntimeMinutes(meta.runtimeMinutes());
            movie.setRating(meta.rating());
            movie.setCertification(meta.certification());
            movie.setStudio(meta.studio());
            movie.setReleaseDate(meta.releaseDate());
            movie.setTmdbId(meta.tmdbId());
            movie.setImdbId(meta.imdbId());
            movie.setDirectors(joinOrNull(meta.directors(), 1024));
            movie.setCastMembers(joinOrNull(meta.cast(), 4000));
            replaceGenres(movie, meta.genres());
            String explicitSort = meta.sortTitle() == null
                    ? null
                    : meta.sortTitle().toLowerCase(Locale.ROOT);
            movie.setSortTitle(orElse(explicitSort, FilenameParser.sortTitle(movie.getTitle())));
        } else {
            movie.setMetadataSource(MetadataSource.FILENAME);
            movie.setTitle(parsed.title());
            movie.setYear(parsed.year());
            movie.setSortTitle(FilenameParser.sortTitle(parsed.title()));
            replaceGenres(movie, Set.of());
        }

        // Re-resolved every time: artwork is often added to a folder after the first scan.
        movie.setPosterPath(sidecars.findPoster(file).map(Path::toString).orElse(null));
        movie.setBackdropPath(sidecars.findBackdrop(file).map(Path::toString).orElse(null));
    }

    /**
     * Mutates the managed collection in place. Assigning a fresh {@code Set} to a
     * Hibernate-owned {@code @ElementCollection} throws once the entity is managed.
     */
    private static void replaceGenres(Movie movie, Set<String> genres) {
        Set<String> target = movie.getGenres();
        if (target == null) {
            movie.setGenres(new LinkedHashSet<>(genres));
            return;
        }
        target.clear();
        target.addAll(genres);
    }

    private void probeIfNeeded(Movie movie, Path file) {
        if (!props.isProbeOnScan()) {
            return;
        }
        if (movie.getMediaInfo() != null && movie.getMediaInfo().isProbed()) {
            return;
        }
        probe.probe(file).ifPresent(movie::setMediaInfo);
    }

    private static String orElse(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static String joinOrNull(List<String> values, int maxLength) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String joined = String.join(", ", values);
        return joined.length() <= maxLength ? joined : joined.substring(0, maxLength);
    }
}
