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

import com.example.kido.media.MediaFiles;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.catalog.MetadataSource;
import com.example.kido.media.metadata.FilenameParser;
import com.example.kido.media.metadata.NfoParser;
import com.example.kido.media.metadata.SidecarLocator;
import com.example.kido.media.metadata.SidecarMetadata;
import com.example.kido.media.probe.MediaChapter;
import com.example.kido.media.probe.MediaChapterRepository;
import com.example.kido.media.probe.MediaProbe;

import lombok.extern.slf4j.Slf4j;

/**
 * Reconciles a single file on disk with its {@code media_items} row.
 *
 * <p>Deliberately a separate bean from {@link LibraryScanner}: {@code @Transactional}
 * is applied by a proxy, so a scanner calling its own annotated method would silently
 * run with no transaction at all. Each file commits on its own, which also means a scan
 * that dies halfway leaves everything it already indexed intact.
 */
@Slf4j
@Service
public class LibraryIngestService {

    private final MediaProperties props;
    private final MediaItemRepository items;
    private final MediaChapterRepository chapters;
    private final NfoParser nfoParser;
    private final SidecarLocator sidecars;
    private final FilenameParser filenames;
    private final MediaProbe probe;

    public LibraryIngestService(MediaProperties props,
                                MediaItemRepository items,
                                MediaChapterRepository chapters,
                                NfoParser nfoParser,
                                SidecarLocator sidecars,
                                FilenameParser filenames,
                                MediaProbe probe) {
        this.props = props;
        this.items = items;
        this.chapters = chapters;
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
    public Outcome ingest(Path file, MediaPaths.LibraryRoot library) throws IOException {
        String fileName = file.getFileName().toString();

        MediaType type = typeFor(file, library);
        if (type == null) {
            return Outcome.SKIPPED;
        }
        if (type.isVideo() && MediaFiles.looksLikeExtra(fileName)) {
            return Outcome.SKIPPED;
        }
        // Cover art living beside a film must not become a photo library entry.
        if (type == MediaType.PHOTO && MediaFiles.isArtworkImage(fileName)) {
            return Outcome.SKIPPED;
        }

        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        // Only videos get the size floor: photos and songs are legitimately small.
        if (type.isVideo() && props.getMinFileSizeMb() > 0) {
            long minBytes = props.getMinFileSizeMb() * 1024L * 1024L;
            if (attrs.size() < minBytes) {
                log.debug("Skipping {} ({} bytes, below configured minimum)", fileName, attrs.size());
                return Outcome.SKIPPED;
            }
        }

        String absolutePath = file.toAbsolutePath().normalize().toString();
        Instant modifiedAt = attrs.lastModifiedTime().toInstant();
        Optional<MediaItem> existing = items.findByFilePath(absolutePath);

        if (existing.isPresent()) {
            MediaItem item = existing.get();
            boolean contentUnchanged = item.getFileSize() == attrs.size()
                    && modifiedAt.equals(item.getFileModifiedAt());

            // The cheap path that makes rescanning a large library viable.
            if (contentUnchanged && item.getMetadataSource() != null && !item.isMissing()) {
                return Outcome.UNCHANGED;
            }

            applyMetadata(item, file, type, library, attrs, modifiedAt);
            if (!contentUnchanged) {
                // The bytes changed, so the cached probe describes a file that is gone.
                item.setMediaInfo(new MediaInfo());
                chapters.deleteByMediaItemId(item.getId());
            }
            item.setMissing(false);
            item.setUpdatedAt(Instant.now());
            MediaItem saved = items.save(item);
            probeIfNeeded(saved, file);
            return Outcome.UPDATED;
        }

        MediaItem item = MediaItem.builder()
                .type(type)
                .filePath(absolutePath)
                .fileName(fileName)
                .title(fileName)
                .build();
        applyMetadata(item, file, type, library, attrs, modifiedAt);
        MediaItem saved = items.save(item);
        probeIfNeeded(saved, file);
        return Outcome.ADDED;
    }

    /**
     * The type to index a file as, or null to skip it.
     *
     * <p>The library's configured type wins, but only when the file's own kind agrees:
     * a stray MP3 in a film library is indexed as music rather than as a silent film,
     * and a video dropped into a photo library stays a video.
     */
    private static MediaType typeFor(Path file, MediaPaths.LibraryRoot library) {
        Optional<MediaType.Kind> kind = MediaFiles.kindOf(file);
        if (kind.isEmpty()) {
            return null;
        }
        if (kind.get() == library.type().kind()) {
            return library.type();
        }
        return switch (kind.get()) {
            // A video in a photo library is most likely home footage.
            case VIDEO -> library.type() == MediaType.PHOTO ? MediaType.HOME_VIDEO : MediaType.FILM;
            case AUDIO -> MediaType.MUSIC;
            case IMAGE -> MediaType.PHOTO;
        };
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
        List<MediaItem> gone = new ArrayList<>();
        for (MediaItem item : items.findByMissingFalse()) {
            if (!seen.contains(item.getFilePath())) {
                item.setMissing(true);
                item.setUpdatedAt(Instant.now());
                gone.add(item);
            }
        }
        if (!gone.isEmpty()) {
            items.saveAll(gone);
            log.info("Marked {} items as missing", gone.size());
        }
        return gone.size();
    }

    /** Probes on demand for a row indexed while {@code probe-on-scan} was off. */
    @Transactional
    public MediaItem ensureProbed(MediaItem item, Path file) {
        if (!item.getType().isVideo()) {
            return item;
        }
        if (item.getMediaInfo() != null && item.getMediaInfo().isProbed()) {
            return item;
        }
        return applyProbe(item, file);
    }

    /** Runs ffprobe and stores both the stream info and the chapter markers. */
    private MediaItem applyProbe(MediaItem item, Path file) {
        Optional<MediaProbe.ProbeResult> probed = probe.probe(file);
        if (probed.isEmpty()) {
            return item;
        }
        MediaProbe.ProbeResult result = probed.get();
        item.setMediaInfo(result.info());

        // Duration from the container is more trustworthy than a sidecar's runtime.
        if (result.info().getDurationSeconds() != null && item.getRuntimeMinutes() == null) {
            item.setRuntimeMinutes((int) Math.round(result.info().getDurationSeconds() / 60.0));
        }
        item.setUpdatedAt(Instant.now());
        MediaItem saved = items.save(item);

        chapters.deleteByMediaItemId(saved.getId());
        if (!result.chapters().isEmpty()) {
            List<MediaChapter> rows = result.chapters().stream()
                    .map(chapter -> MediaChapter.builder()
                            .mediaItemId(saved.getId())
                            .chapterIndex(chapter.index())
                            .startSeconds(chapter.startSeconds())
                            .endSeconds(chapter.endSeconds())
                            .title(chapter.title())
                            .build())
                    .toList();
            chapters.saveAll(rows);
        }
        return saved;
    }

    /** Fills descriptive fields from the sidecar when there is one, else from the file. */
    private void applyMetadata(MediaItem item,
                               Path file,
                               MediaType type,
                               MediaPaths.LibraryRoot library,
                               BasicFileAttributes attrs,
                               Instant modifiedAt) {

        String fileName = file.getFileName().toString();
        Path folder = file.getParent();

        // Facts about the file always refresh — they describe the bytes, not the guess.
        item.setLibraryName(library.name());
        item.setFileName(fileName);
        item.setFileSize(attrs.size());
        item.setFileModifiedAt(modifiedAt);
        item.setFolderPath(folder == null ? null : folder.toAbsolutePath().normalize().toString());

        // A hand-set type outlives the library root it was found under.
        if (!item.isTypeLocked()) {
            item.setType(type);
        }

        // A correction the owner made by hand must outlast the scanner that got it
        // wrong. Without this, fixing a title would be undone by the next rescan.
        if (!item.isMetadataScannerOwned()) {
            log.debug("Keeping manual metadata for {}", fileName);
            return;
        }

        // The item's own type, not the derived one: a locked reclassify decides which
        // shape of metadata this file gets, so a clip moved to "Ours" is described by
        // capture date rather than being re-parsed as a film release name.
        MediaType effectiveType = item.getType();

        if (effectiveType.isTimeline()) {
            applyTimelineMetadata(item, file, attrs);
            return;
        }
        if (effectiveType == MediaType.MUSIC) {
            applyMusicMetadata(item, file);
            return;
        }
        applyVideoMetadata(item, file, fileName);
    }

    /**
     * Home footage and photos are described by when and where, not by year and rating.
     *
     * <p>Capture time is taken from the filename when it carries a date (phones and
     * cameras almost always do) and otherwise from the file's creation time. That is a
     * guess, so it is only used when it predates the modification time — a file copied
     * between disks has a creation time of when it was copied, which would be wrong.
     */
    private void applyTimelineMetadata(MediaItem item, Path file, BasicFileAttributes attrs) {
        String base = MediaFiles.baseName(file.getFileName().toString());
        item.setMetadataSource(MetadataSource.FILENAME);
        item.setTitle(prettifyTimelineTitle(base));
        item.setSortTitle(FilenameParser.sortTitle(item.getTitle()));

        Optional<Instant> fromName = filenames.captureInstant(base);
        if (fromName.isPresent()) {
            item.setCapturedAt(fromName.get());
        } else {
            Instant created = attrs.creationTime().toInstant();
            Instant modified = attrs.lastModifiedTime().toInstant();
            item.setCapturedAt(created.isBefore(modified) ? created : modified);
        }
        // Folder name is the best available guess at a place, e.g. "Goa 2023".
        Path folder = file.getParent();
        if (folder != null && folder.getFileName() != null) {
            item.setPlace(folder.getFileName().toString());
        }
        item.setPosterPath(null);
        item.setBackdropPath(null);
        replaceStrings(item.getGenres(), Set.of(), item::setGenres);
    }

    /**
     * Music metadata comes from the path, not from ID3 tags.
     *
     * <p>Reading tags would need another dependency; {@code Artist/Album/01 Track.mp3}
     * is the near-universal layout and gets the three fields the client shows.
     */
    private void applyMusicMetadata(MediaItem item, Path file) {
        String base = MediaFiles.baseName(file.getFileName().toString());
        item.setMetadataSource(MetadataSource.FILENAME);

        // Leading track numbers: "01 - Title" or "01. Title".
        String title = base.replaceFirst("^\\s*(\\d{1,3})\\s*[-._)]?\\s+", "");
        Integer track = null;
        if (!title.equals(base)) {
            try {
                track = Integer.valueOf(base.trim().split("[^0-9]", 2)[0]);
            } catch (NumberFormatException ignored) {
                // Leave the track number unset; the title is still improved.
            }
        }
        item.setTitle(title.isBlank() ? base : title.trim());
        item.setSortTitle(FilenameParser.sortTitle(item.getTitle()));
        item.setTrackNumber(track);

        Path folder = file.getParent();
        if (folder != null && folder.getFileName() != null) {
            item.setAlbum(folder.getFileName().toString());
            Path artistFolder = folder.getParent();
            if (artistFolder != null && artistFolder.getFileName() != null) {
                item.setArtist(artistFolder.getFileName().toString());
            }
        }
        item.setPosterPath(sidecars.findPoster(file).map(Path::toString).orElse(null));
    }

    /** Films and anime: sidecar {@code .nfo} first, filename as the fallback. */
    private void applyVideoMetadata(MediaItem item, Path file, String fileName) {
        FilenameParser.Parsed parsed = filenames.parse(MediaFiles.baseName(fileName));
        item.setQuality(parsed.quality());

        Optional<SidecarMetadata> sidecar = sidecars.findNfo(file).flatMap(nfoParser::parse);

        if (sidecar.isPresent()) {
            SidecarMetadata meta = sidecar.get();
            item.setMetadataSource(MetadataSource.NFO);
            // The sidecar wins, but field by field: partial .nfo files are common and
            // must not blank out what the filename could still supply.
            item.setTitle(orElse(meta.title(), parsed.title()));
            item.setOriginalTitle(meta.originalTitle());
            item.setYear(meta.year() != null ? meta.year() : parsed.year());
            item.setPlot(meta.plot());
            item.setTagline(meta.tagline());
            item.setRuntimeMinutes(meta.runtimeMinutes());
            item.setRating(meta.rating());
            item.setCertification(meta.certification());
            item.setStudio(meta.studio());
            item.setReleaseDate(meta.releaseDate());
            item.setTmdbId(meta.tmdbId());
            item.setImdbId(meta.imdbId());
            item.setDirectors(joinOrNull(meta.directors(), 1024));
            item.setCastMembers(joinOrNull(meta.cast(), 4000));
            replaceStrings(item.getGenres(), meta.genres(), item::setGenres);
            String explicitSort = meta.sortTitle() == null
                    ? null
                    : meta.sortTitle().toLowerCase(Locale.ROOT);
            item.setSortTitle(orElse(explicitSort, FilenameParser.sortTitle(item.getTitle())));
        } else {
            item.setMetadataSource(MetadataSource.FILENAME);
            item.setTitle(parsed.title());
            item.setYear(parsed.year());
            item.setSortTitle(FilenameParser.sortTitle(parsed.title()));
            replaceStrings(item.getGenres(), Set.of(), item::setGenres);
        }

        // Re-resolved every scan: artwork is often added to a folder afterwards.
        item.setPosterPath(sidecars.findPoster(file).map(Path::toString).orElse(null));
        item.setBackdropPath(sidecars.findBackdrop(file).map(Path::toString).orElse(null));
    }

    /** {@code VID_20240102_181500} and friends read badly as titles. */
    private static String prettifyTimelineTitle(String base) {
        String cleaned = base
                .replaceFirst("^(?i)(VID|IMG|PXL|DSC|MOV|DCIM)[-_]?", "")
                .replaceAll("[._]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return cleaned.isBlank() ? base : cleaned;
    }

    /**
     * Mutates the managed collection in place. Assigning a fresh {@code Set} to a
     * Hibernate-owned {@code @ElementCollection} throws once the entity is managed.
     */
    private static void replaceStrings(Set<String> target,
                                       Set<String> values,
                                       java.util.function.Consumer<Set<String>> setter) {
        if (target == null) {
            setter.accept(new LinkedHashSet<>(values));
            return;
        }
        target.clear();
        target.addAll(values);
    }

    private void probeIfNeeded(MediaItem item, Path file) {
        if (!props.isProbeOnScan() || !item.getType().isVideo()) {
            return;
        }
        if (item.getMediaInfo() != null && item.getMediaInfo().isProbed()) {
            return;
        }
        applyProbe(item, file);
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
