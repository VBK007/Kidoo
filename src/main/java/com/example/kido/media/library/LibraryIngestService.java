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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.MediaFiles;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.cast.CastPhotoService;
import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.catalog.MetadataSource;
import com.example.kido.media.metadata.FilenameParser;
import com.example.kido.media.metadata.Languages;
import com.example.kido.media.metadata.NfoParser;
import com.example.kido.media.metadata.SidecarLocator;
import com.example.kido.media.metadata.SidecarMetadata;
import com.example.kido.media.music.AudioFeatureService;
import com.example.kido.media.music.MoodClassifier;
import com.example.kido.media.music.SiteWatermark;
import com.example.kido.media.music.TrackArtworkService;
import com.example.kido.media.probe.ImageProbe;
import com.example.kido.media.probe.MediaChapter;
import com.example.kido.media.probe.MediaChapterRepository;
import com.example.kido.media.probe.MediaProbe;
import com.example.kido.media.tmdb.TmdbMovieService;

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

    /** Rows per page when reconciling the library against the disk. */
    private static final int RECONCILE_PAGE_SIZE = 500;

    private final MediaProperties props;
    private final MediaPaths paths;
    private final MediaItemRepository items;
    private final MediaChapterRepository chapters;
    private final NfoParser nfoParser;
    private final SidecarLocator sidecars;
    private final FilenameParser filenames;
    private final MediaProbe probe;
    private final ImageProbe images;
    private final TmdbMovieService tmdbMovies;
    private final CastPhotoService castPhotos;
    private final TrackArtworkService trackArtwork;
    private final AudioFeatureService audioFeatures;

    public LibraryIngestService(MediaProperties props,
                                MediaPaths paths,
                                MediaItemRepository items,
                                MediaChapterRepository chapters,
                                NfoParser nfoParser,
                                SidecarLocator sidecars,
                                FilenameParser filenames,
                                MediaProbe probe,
                                ImageProbe images,
                                TmdbMovieService tmdbMovies,
                                CastPhotoService castPhotos,
                                TrackArtworkService trackArtwork,
                                AudioFeatureService audioFeatures) {
        this.props = props;
        this.paths = paths;
        this.items = items;
        this.chapters = chapters;
        this.nfoParser = nfoParser;
        this.sidecars = sidecars;
        this.filenames = filenames;
        this.probe = probe;
        this.images = images;
        this.tmdbMovies = tmdbMovies;
        this.castPhotos = castPhotos;
        this.trackArtwork = trackArtwork;
        this.audioFeatures = audioFeatures;
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
        // The whole path, not just the name: the strongest signal that an image is
        // artwork is a playable file of the same name sitting next to it.
        if (type == MediaType.PHOTO && MediaFiles.isArtworkImage(file)) {
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
                applyLanguages(item);
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
        int marked = 0;

        // Walked a page at a time rather than loaded whole. Every indexed row has to be
        // examined — that is what reconciliation means — but holding a terabyte library
        // in memory to do it is avoidable. Paging backwards is deliberate: marking a row
        // missing removes it from this query's result set, so advancing the page number
        // would skip rows as the set shrinks underneath.
        int pageNumber = 0;
        while (true) {
            Page<MediaItem> page = items.findByMissingFalse(
                    PageRequest.of(pageNumber, RECONCILE_PAGE_SIZE, Sort.by("id")));
            if (page.isEmpty()) {
                break;
            }
            List<MediaItem> gone = new ArrayList<>();
            for (MediaItem item : page.getContent()) {
                // A catalogued title has no file for the walk to have seen, and marking
                // it missing would hide every entry the admin API ever created.
                if (item.isCatalogOnly()) {
                    continue;
                }
                if (!seen.contains(item.getFilePath())) {
                    item.setMissing(true);
                    item.setUpdatedAt(Instant.now());
                    gone.add(item);
                }
            }
            if (!gone.isEmpty()) {
                items.saveAll(gone);
                items.flush();
                marked += gone.size();
                // Those rows have left the result set, so stay on this page number.
            } else {
                pageNumber++;
            }
            if (!page.hasNext() && gone.isEmpty()) {
                break;
            }
        }

        if (marked > 0) {
            log.info("Marked {} items as missing", marked);
        }
        return marked;
    }

    /** Below this, a poster is a banner-style thumbnail rather than real key art. */
    private static final int MIN_POSTER_WIDTH = 500;
    private static final int MIN_POSTER_HEIGHT = 750;

    /**
     * Re-derives artwork for every video with none, and upgrades one that is present
     * but too small to be a real poster.
     *
     * <p>{@link #ingest} only resolves {@code posterPath}/{@code backdropPath} for a
     * file it treats as new or changed — a file whose size and modified time already
     * match its row takes the cheap {@link Outcome#UNCHANGED} path and skips metadata
     * entirely, artwork included. That means an improvement to how artwork is matched
     * never reaches an already-indexed file on its own, so the scanner calls this once
     * at the end of every pass: cheap for a home library (one filesystem check per row,
     * one ffprobe per poster) and it means a looser match, a shared posters folder, or
     * artwork simply added after the fact all catch up within one scan interval instead
     * of needing a manual trigger.
     *
     * <p>An existing poster is only ever replaced by one that clears the size floor
     * itself — trading a bad thumbnail for an equally bad one on every single scan would
     * be pure churn with nothing to show for it.
     *
     * @return how many items' artwork changed
     */
    @Transactional
    public int backfillArtwork() {
        List<MediaItem> updated = new ArrayList<>();
        for (MediaItem item : items.findByMissingFalse()) {
            boolean changed;
            if (item.getType().isVideo()) {
                changed = backfillVideoArtwork(item);
            } else if (item.getType() == MediaType.MUSIC) {
                changed = backfillTrackArtwork(item);
            } else {
                continue;
            }
            if (changed) {
                item.setUpdatedAt(Instant.now());
                updated.add(item);
            }
        }
        items.saveAll(updated);
        return updated.size();
    }

    private boolean backfillVideoArtwork(MediaItem item) {
        Path file = Path.of(item.getFilePath());
        boolean changed = false;

        if (!item.hasPoster()) {
            String poster = findAnyPoster(file, item.getTitle());
            if (poster != null) {
                item.setPosterPath(poster);
                changed = true;
            }
        } else if (isBelowPosterFloor(item.getPosterPath())) {
            String upgrade = titleMatchedPoster(file, item.getTitle());
            if (upgrade != null && !upgrade.equals(item.getPosterPath())
                    && !isBelowPosterFloor(upgrade)) {
                item.setPosterPath(upgrade);
                changed = true;
            }
        }

        if (!item.hasBackdrop()) {
            String backdrop = sidecars.findBackdrop(file).map(Path::toString).orElse(null);
            if (backdrop != null) {
                item.setBackdropPath(backdrop);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * At most one iTunes lookup per track, ever: {@code musicArtworkCheckedAt} is
     * stamped whether or not a match was found, which is what stops a track this API
     * simply has nothing for from being re-queried on every future scan.
     */
    private boolean backfillTrackArtwork(MediaItem item) {
        if (item.hasPoster() || item.getMusicArtworkCheckedAt() != null) {
            return false;
        }
        String artwork = trackArtwork.fetchAndStore(item);
        item.setMusicArtworkCheckedAt(Instant.now());
        if (artwork != null) {
            item.setPosterPath(artwork);
        }
        return true;
    }

    /**
     * Analyzes every audio track's own tempo/energy/brightness and stores the mood and
     * activity rail it belongs on — the music home screen's whole reason for existing.
     * Same shape as {@link #backfillArtwork()}: a separate pass over every present item
     * rather than part of {@link #ingest}, because {@code ingest} skips metadata entirely
     * for an unchanged file, and {@code audioAnalyzedAt} is what stops a track aubio
     * simply cannot decode from being re-decoded on every future scan regardless.
     *
     * @return how many tracks were analyzed (successfully or not — stamping happens either way)
     */
    @Transactional
    public int backfillAudioFeatures() {
        List<MediaItem> updated = new ArrayList<>();
        for (MediaItem item : items.findByMissingFalse()) {
            if ((item.getType() != MediaType.MUSIC && item.getType() != MediaType.VIDEO_SONG)
                    || item.getAudioAnalyzedAt() != null) {
                continue;
            }
            audioFeatures.analyze(Path.of(item.getFilePath())).ifPresent(features -> {
                item.setBpm(features.bpm());
                item.setEnergyRms(features.energyRms());
                item.setSpectralCentroid(features.spectralCentroid());
                MoodClassifier.classify(features.bpm(), features.energyRms(), features.spectralCentroid())
                        .ifPresent(result -> {
                            item.setMood(result.mood());
                            item.setActivity(result.activity());
                        });
            });
            item.setAudioAnalyzedAt(Instant.now());
            updated.add(item);
        }
        items.saveAll(updated);
        return updated.size();
    }

    /**
     * Fills in plot, rating and cast from TMDB for every video missing any of them, and
     * pre-warms the cast photo cache for every credited name — all open, free lookups,
     * run automatically at the end of every scan so new movies get them without anyone
     * asking.
     *
     * <p>A field that already has a value is never touched, and enrichment is only
     * attempted at all when {@link MediaItem#isMetadataScannerOwned()} — the same
     * guard {@link #applyMetadata} uses before overwriting anything else, so data
     * someone edited by hand is exactly as safe from this as every other manually-set
     * field already is. Cast photos need no such guard: they never write to {@code
     * MediaItem} at all, only to their own cache keyed by name, and re-warming an
     * already-cached name is a local lookup, not a TMDB call — see {@link
     * CastPhotoService#photoFor}.
     *
     * @return how many items got at least one of these fields they did not have before
     */
    @Transactional
    public int backfillMetadata() {
        List<MediaItem> updated = new ArrayList<>();
        for (MediaItem item : items.findByMissingFalse()) {
            if (!item.getType().isVideo()) {
                continue;
            }
            boolean missingSomething = item.getPlot() == null || item.getPlot().isBlank()
                    || item.getRating() == null
                    || item.getCastMembers() == null || item.getCastMembers().isBlank();
            if (item.isMetadataScannerOwned() && missingSomething && tmdbMovies.enrich(item)) {
                item.setUpdatedAt(Instant.now());
                updated.add(item);
            }
            for (String name : splitCastMembers(item.getCastMembers())) {
                castPhotos.photoFor(name);
            }
        }
        items.saveAll(updated);
        return updated.size();
    }

    private static List<String> splitCastMembers(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return List.of(joined.split("\\s*,\\s*"));
    }

    /**
     * Restates every item's languages from the probe it already has.
     *
     * <p>The languages are a view of {@code probe_audio_tracks}, which existing rows
     * have been carrying since they were first scanned — so this reads a column nobody
     * was querying rather than touching a disk. No ffprobe, no rescan: a library indexed
     * long before the facet existed gets it in one pass.
     *
     * <p>Also the repair path for a normalisation change. The mapping from a container's
     * tag to a canonical code is a guess about spellings encoders use; when it improves,
     * this is what re-applies it to rows that were stored under the old answer.
     *
     * @return how many items' languages changed
     */
    @Transactional
    public int backfillLanguages() {
        List<MediaItem> updated = new ArrayList<>();
        for (MediaItem item : items.findByMissingFalse()) {
            MediaInfo info = item.getMediaInfo();
            if (info == null || info.getAudioTracks() == null) {
                continue;
            }
            Set<String> derived = Languages.fromAudioTracks(info.getAudioTracks());
            String primary = derived.isEmpty() ? null : derived.iterator().next();

            if (derived.equals(item.getLanguages())
                    && Objects.equals(primary, item.getPrimaryLanguage())) {
                continue;
            }
            item.getLanguages().clear();
            item.getLanguages().addAll(derived);
            item.setPrimaryLanguage(primary);
            item.setUpdatedAt(Instant.now());
            updated.add(item);
        }
        items.saveAll(updated);
        return updated.size();
    }


    private String findAnyPoster(Path file, String title) {
        String poster = sidecars.findPoster(file).map(Path::toString).orElse(null);
        return poster != null ? poster : titleMatchedPoster(file, title);
    }

    /**
     * Nothing beside the file itself; try every configured library's shared posters
     * folder, matched by title rather than by anything about the video.
     *
     * <p>Every root is checked, not just the one this file happens to live in: a
     * posters folder is a household convenience, filed under whichever library was
     * open at the time, and there is no reason its coverage should stop at that
     * library's own boundary when a film in a different root shares the exact same
     * title. The item's own library is tried first, purely so the common case (a
     * posters folder actually meant for this library) does not pay for checking the
     * others.
     */
    private String titleMatchedPoster(Path file, String title) {
        Optional<MediaPaths.LibraryRoot> own = paths.libraryOf(file);
        Optional<Path> ownMatch = own.flatMap(root -> sidecars.findPosterByTitle(root.path(), title));
        if (ownMatch.isPresent()) {
            return ownMatch.get().toString();
        }
        for (MediaPaths.LibraryRoot root : paths.libraryRoots()) {
            if (own.isPresent() && root.path().equals(own.get().path())) {
                continue;
            }
            Optional<Path> match = sidecars.findPosterByTitle(root.path(), title);
            if (match.isPresent()) {
                return match.get().toString();
            }
        }
        return null;
    }

    /** Unreadable counts as "not below the floor" — nothing to churn toward if the
     * current poster's own dimensions cannot be determined. */
    private boolean isBelowPosterFloor(String posterPath) {
        if (posterPath == null) {
            return true;
        }
        return images.dimensions(Path.of(posterPath))
                .map(d -> !d.atLeast(MIN_POSTER_WIDTH, MIN_POSTER_HEIGHT))
                .orElse(false);
    }

    /** Probes on demand for a row indexed while {@code probe-on-scan} was off. */
    @Transactional
    public MediaItem ensureProbed(MediaItem item, Path file) {
        if (item.getType().kind() == MediaType.Kind.IMAGE) {
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
        applyLanguages(item);

        // Duration from the container is more trustworthy than a sidecar's runtime.
        if (result.info().getDurationSeconds() != null && item.getRuntimeMinutes() == null) {
            item.setRuntimeMinutes((int) Math.round(result.info().getDurationSeconds() / 60.0));
        }
        if (item.getType() == MediaType.MUSIC) {
            applyAudioTags(item, result.audioTags());
            // The "video" stream ffprobe just found on an audio file is virtually
            // always an embedded cover (ID3 APIC and equivalents report that way) —
            // extract it once, unless a sidecar image already won during ingest.
            if (!item.hasPoster() && result.info().getVideoCodec() != null) {
                String cover = extractEmbeddedCoverArt(item, file);
                if (cover != null) {
                    item.setPosterPath(cover);
                }
            }
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
        String cleanedTitle = SiteWatermark.clean(title.isBlank() ? base : title.trim());
        item.setTitle(cleanedTitle == null ? base : cleanedTitle);
        item.setSortTitle(FilenameParser.sortTitle(item.getTitle()));
        item.setTrackNumber(track);

        Path folder = file.getParent();
        if (folder != null && folder.getFileName() != null) {
            item.setAlbum(SiteWatermark.clean(folder.getFileName().toString()));
            Path artistFolder = folder.getParent();
            if (artistFolder != null && artistFolder.getFileName() != null) {
                item.setArtist(SiteWatermark.clean(artistFolder.getFileName().toString()));
            }
        }
        // Additive only: a sidecar image found now is worth taking, but finding none
        // this time must never clear a poster that came from embedded art or the
        // iTunes fallback — both run after this method, on files no sidecar ever had.
        sidecars.findPoster(file).map(Path::toString).ifPresent(item::setPosterPath);
    }

    /**
     * Prefers a track's own embedded tags over {@link #applyMusicMetadata}'s
     * folder/filename guess, field by field — the same precedence
     * {@link #applyVideoMetadata} gives an {@code .nfo} sidecar over a parsed filename.
     * A file with no tags at all (or only some) keeps the guess for whatever is missing.
     */
    private void applyAudioTags(MediaItem item, MediaProbe.AudioTags tags) {
        if (tags == null) {
            return;
        }
        String title = SiteWatermark.clean(tags.title());
        if (title != null) {
            item.setTitle(title);
            item.setSortTitle(FilenameParser.sortTitle(title));
        }
        String artist = SiteWatermark.clean(tags.artist());
        if (artist != null) {
            item.setArtist(artist);
        }
        String album = SiteWatermark.clean(tags.album());
        if (album != null) {
            item.setAlbum(album);
        }
        if (tags.track() != null) {
            item.setTrackNumber(tags.track());
        }
        // Additive only, like posters: a composer tag stripped to nothing this scan (or
        // simply absent on this file) must never clear a value a previous scan — or the
        // one-time verified backfill this app shipped with — already resolved correctly.
        String composer = SiteWatermark.clean(tags.composer());
        if (composer != null) {
            item.setMusicDirector(composer);
        }
        if (tags.year() != null && item.getYear() == null) {
            item.setYear(tags.year());
        }
    }

    /**
     * Pulls a track's embedded cover out to a normal poster file, the same way an
     * admin-uploaded poster is stored ({@code <artworkDir>/<itemId>/poster.jpg}), so
     * {@code ArtworkController}'s existing {@code /poster} endpoint needs no changes to
     * serve it. Re-encodes to JPEG rather than copying the stream verbatim — the
     * embedded picture is occasionally PNG, and a fixed, known-decodable output format
     * is worth a re-encode that costs nothing on an image this small.
     *
     * @return the absolute path written, or null if ffmpeg found nothing to extract
     */
    private String extractEmbeddedCoverArt(MediaItem item, Path file) {
        Path directory = paths.artworkDir().resolve(item.getId());
        Path target = directory.resolve("poster.jpg");
        try {
            Files.createDirectories(directory);
            List<String> command = List.of(
                    props.getFfmpegPath(),
                    "-y", "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-i", file.toString(),
                    "-map", "0:v:0", "-vframes", "1", "-vcodec", "mjpeg",
                    target.toString());
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(props.getProbeTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("Cover art extraction timed out for {}", file);
                return null;
            }
            if (process.exitValue() != 0 || !Files.exists(target) || Files.size(target) == 0) {
                log.debug("No embedded cover art in {}", file);
                Files.deleteIfExists(target);
                return null;
            }
            return target.toString();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException ex) {
            log.warn("Cover art extraction failed for {}: {}", file, ex.getMessage());
            return null;
        }
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

        // Additive only: a sidecar found now is worth taking (artwork is often added to
        // a folder after the fact, which is why this re-checks every scan at all), but
        // finding none this time must never clear a poster/backdrop that came from
        // somewhere else — TMDB backfill, an admin upload, or a track's embedded art.
        // backfillArtwork() is what already handles "no poster yet, try harder"; this
        // is only ever supposed to opportunistically upgrade, never regress.
        sidecars.findPoster(file).map(Path::toString).ifPresent(item::setPosterPath);
        sidecars.findBackdrop(file).map(Path::toString).ifPresent(item::setBackdropPath);
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

    /**
     * Restates an item's languages from whatever its probe currently says.
     *
     * <p>Called on both sides of a probe — after one runs, and when the bytes changed
     * so the cached probe is discarded — because the languages are a view of the audio
     * tracks and must not outlive them. Re-derived wholesale rather than merged: a
     * re-muxed file that lost its Hindi track should lose the facet too.
     */
    private static void applyLanguages(MediaItem item) {
        MediaInfo info = item.getMediaInfo();
        Set<String> languages = info == null
                ? Set.of()
                : Languages.fromAudioTracks(info.getAudioTracks());

        item.getLanguages().clear();
        item.getLanguages().addAll(languages);
        item.setPrimaryLanguage(languages.isEmpty() ? null : languages.iterator().next());
    }

    private void probeIfNeeded(MediaItem item, Path file) {
        if (!props.isProbeOnScan() || item.getType().kind() == MediaType.Kind.IMAGE) {
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
