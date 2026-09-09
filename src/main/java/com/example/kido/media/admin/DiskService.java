package com.example.kido.media.admin;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.dto.AdminDtos.BigFileDto;
import com.example.kido.media.dto.AdminDtos.CategoryUsageDto;
import com.example.kido.media.dto.AdminDtos.DiskTabDto;
import com.example.kido.media.dto.AdminDtos.PurgeResultDto;
import com.example.kido.media.dto.AdminDtos.ReclaimableDto;
import com.example.kido.media.dto.AdminDtos.VolumeDto;
import com.example.kido.media.playback.PlaybackProgressRepository;
import com.example.kido.profile.ProfileRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Backs the admin panel's Disk tab: what is using the space, and what could be freed.
 *
 * <p>Sizes come from the indexed rows rather than by walking the disk on request — the
 * scan already recorded every file size, and re-walking a multi-terabyte volume to
 * render a settings screen would be indefensible. Free space is the exception: it must
 * be read live from the filesystem, since it changes for reasons the server never sees.
 */
@Slf4j
@Service
public class DiskService {

    /** Watched-by-everyone titles older than this are offered as reclaimable. */
    private static final int STALE_AFTER_MONTHS = 12;

    private final MediaItemRepository items;
    private final PlaybackProgressRepository progress;
    private final ProfileRepository profiles;
    private final MediaPaths paths;
    private final MediaProperties props;

    public DiskService(MediaItemRepository items,
                       PlaybackProgressRepository progress,
                       ProfileRepository profiles,
                       MediaPaths paths,
                       MediaProperties props) {
        this.items = items;
        this.progress = progress;
        this.profiles = profiles;
        this.paths = paths;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public DiskTabDto diskTab(int biggestFileLimit) {
        return new DiskTabDto(
                categoryUsage(),
                volumes(),
                biggestFiles(biggestFileLimit),
                reclaimable());
    }

    /** The segmented usage bar, one segment per category. */
    @Transactional(readOnly = true)
    public List<CategoryUsageDto> categoryUsage() {
        Map<MediaType, long[]> byType = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : items.countAndBytesByType()) {
            MediaType type = (MediaType) row[0];
            long count = ((Number) row[1]).longValue();
            long bytes = ((Number) row[2]).longValue();
            byType.put(type, new long[]{count, bytes});
            total += bytes;
        }

        List<CategoryUsageDto> usage = new ArrayList<>();
        for (MediaType type : MediaType.values()) {
            long[] stats = byType.getOrDefault(type, new long[]{0L, 0L});
            // Percent of the library, not of the volume: the bar shows how the library
            // divides up, which is the question the screen is asking.
            double percent = total == 0 ? 0 : stats[1] * 100.0 / total;
            usage.add(new CategoryUsageDto(
                    type.name(), type.label(), stats[0], stats[1],
                    Math.round(percent * 10) / 10.0));
        }
        return usage;
    }

    /**
     * Free and total space per distinct volume behind the configured libraries.
     *
     * <p>Deduplicated by {@link FileStore}: two libraries on the same drive are one
     * volume, and reporting the same free space twice would double-count it.
     */
    public List<VolumeDto> volumes() {
        List<VolumeDto> volumes = new ArrayList<>();
        Set<String> seenStores = new HashSet<>();

        for (MediaPaths.LibraryRoot root : paths.libraryRoots()) {
            try {
                FileStore store = Files.getFileStore(root.path());
                if (!seenStores.add(store.name() + "|" + store.type())) {
                    continue;
                }
                long total = store.getTotalSpace();
                long usable = store.getUsableSpace();
                volumes.add(new VolumeDto(
                        root.path().toString(),
                        store.name(),
                        total,
                        usable,
                        total - usable,
                        total == 0 ? 0 : Math.round((total - usable) * 1000.0 / total) / 10.0));
            } catch (IOException ex) {
                log.debug("Could not read volume for {}: {}", root.path(), ex.getMessage());
            }
        }
        return volumes;
    }

    /**
     * Largest files, each with a note explaining why it might be worth attention.
     *
     * <p>The notes are the point of the list: a big file that direct-plays and gets
     * rewatched is fine, whereas a big file that always transcodes costs CPU every
     * time and one nobody has watched is just occupying space.
     */
    @Transactional(readOnly = true)
    public List<BigFileDto> biggestFiles(int limit) {
        int capped = Math.min(Math.max(1, limit), 100);
        Set<String> watchedIds = new HashSet<>(progress.findWatchedItemIds());

        return items.findByMissingFalseAndHiddenFalseOrderByFileSizeDesc(
                        PageRequest.of(0, capped)).stream()
                .map(item -> new BigFileDto(
                        item.getId(),
                        item.getTitle(),
                        item.getType().name(),
                        item.getFileName(),
                        item.getFileSize(),
                        item.getQuality(),
                        item.alwaysTranscodes(),
                        !watchedIds.contains(item.getId()),
                        item.playCount(),
                        noteFor(item, watchedIds.contains(item.getId()))))
                .toList();
    }

    /** The single most useful sentence about this file, or null if there is none. */
    private static String noteFor(MediaItem item, boolean watched) {
        if (item.alwaysTranscodes()) {
            return "always transcodes";
        }
        if (!watched && item.playCount() == 0) {
            return "never watched";
        }
        if (!watched) {
            return "never finished";
        }
        return null;
    }

    /**
     * Space that could be freed without losing anything irreplaceable.
     *
     * <p>Two very different kinds, kept separate because one is safe and one is a
     * judgement call: scratch caches can be deleted with no consequence at all, while
     * "everyone has watched it and it is a year old" is a suggestion for a human to
     * review — hence the client's "Review 228 GB" wording rather than a delete button.
     */
    @Transactional(readOnly = true)
    public ReclaimableDto reclaimable() {
        long transcodeCache = directorySize(Path.of(props.getTranscodeDir()));
        long trickplayCache = directorySize(Path.of(props.getTrickplay().getCacheDir()));
        // Prepared downloads expire on their own, but they are cache all the same.
        long downloadCache = directorySize(Path.of(props.getDownloads().getDir()));

        long profileCount = profiles.count();
        Instant cutoff = Instant.now().minus(STALE_AFTER_MONTHS * 30L, ChronoUnit.DAYS);

        long staleBytes = 0;
        int staleCount = 0;
        if (profileCount > 0) {
            // Watched by every profile, and not touched for a year.
            Map<String, Long> watchedCounts = new LinkedHashMap<>();
            for (Object[] row : progress.countWatchedByItem()) {
                watchedCounts.put((String) row[0], ((Number) row[1]).longValue());
            }
            for (MediaItem item : items.findByMissingFalse()) {
                Long watchers = watchedCounts.get(item.getId());
                boolean everyoneWatched = watchers != null && watchers >= profileCount;
                boolean old = item.getLastPlayedAt() == null
                        ? item.getAddedAt() != null && item.getAddedAt().isBefore(cutoff)
                        : item.getLastPlayedAt().isBefore(cutoff);
                if (everyoneWatched && old) {
                    staleBytes += item.getFileSize();
                    staleCount++;
                }
            }
        }

        return new ReclaimableDto(
                transcodeCache,
                trickplayCache,
                downloadCache,
                staleBytes,
                staleCount,
                transcodeCache + trickplayCache + downloadCache + staleBytes);
    }

    /**
     * Deletes the scratch caches and reports what that freed.
     *
     * <p>Only the two cache directories, never anything under a media library. Both are
     * regenerated on demand — a purged transcode restarts on the next play, and sprite
     * sheets rebuild when the player next asks for them — so this is genuinely safe.
     * Live transcode directories are skipped rather than yanked out from under a viewer.
     */
    public PurgeResultDto purgeCaches() {
        Path transcodeDir = Path.of(props.getTranscodeDir());
        Path trickplayDir = Path.of(props.getTrickplay().getCacheDir());

        long before = directorySize(transcodeDir) + directorySize(trickplayDir);
        int removed = deleteChildren(transcodeDir) + deleteChildren(trickplayDir);
        long after = directorySize(transcodeDir) + directorySize(trickplayDir);
        long freed = Math.max(0, before - after);

        log.info("Purged media caches: {} directories removed, {} freed", removed, freed);
        return new PurgeResultDto(freed,
                removed + " cache " + (removed == 1 ? "directory" : "directories")
                        + " removed; both caches rebuild on demand");
    }

    /**
     * Removes each child directory of a cache root.
     *
     * <p>A directory still being written by a running ffmpeg fails to delete on Windows
     * and is left alone, which is the behaviour we want — a live stream should not break
     * because someone tidied up.
     */
    private int deleteChildren(Path root) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (deleteRecursively(child)) {
                    removed++;
                }
            }
        } catch (IOException ex) {
            log.debug("Could not list cache dir {}: {}", root, ex.getMessage());
        }
        return removed;
    }

    /** @return true only if the directory is fully gone afterwards */
    private boolean deleteRecursively(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // In use by a live ffmpeg; leave it for the next purge.
                }
            });
        } catch (IOException ex) {
            log.debug("Could not delete {}: {}", directory, ex.getMessage());
        }
        return !Files.exists(directory);
    }

    /** Best-effort recursive size; a cache directory may vanish mid-walk. */
    private static long directorySize(Path directory) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ex) {
                    return 0;
                }
            }).sum();
        } catch (IOException ex) {
            log.debug("Could not size {}: {}", directory, ex.getMessage());
            return 0;
        }
    }
}
