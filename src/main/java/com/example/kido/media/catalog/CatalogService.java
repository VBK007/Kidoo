package com.example.kido.media.catalog;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.dto.CatalogDtos.CategoryDto;
import com.example.kido.media.dto.CatalogDtos.ItemDetailDto;
import com.example.kido.media.dto.CatalogDtos.ItemPageDto;
import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;
import com.example.kido.media.dto.CatalogDtos.LibrarySummaryDto;
import com.example.kido.media.dto.CatalogDtos.MediaInfoDto;
import com.example.kido.media.dto.CatalogDtos.SubtitleTrackDto;
import com.example.kido.media.dto.CatalogDtos.TimelineDto;
import com.example.kido.media.dto.CatalogDtos.TimelineGroupDto;
import com.example.kido.media.dto.PlayerDtos.AudioTrackDto;
import com.example.kido.media.metadata.SidecarLocator;
import com.example.kido.media.playback.PlaybackProgress;
import com.example.kido.media.playback.PlaybackService;
import com.example.kido.profile.Profile;

import jakarta.persistence.criteria.JoinType;
import lombok.extern.slf4j.Slf4j;

/**
 * Browsing, searching and detail lookups over the indexed library.
 *
 * <p>Filters are optional and combinable, so they are assembled as JPA
 * {@link Specification}s: a predicate for an absent filter is simply not added. The
 * JPQL alternative — {@code where (:genre is null or g = :genre)} — makes PostgreSQL
 * fail to infer the bind parameter's type when the value is null.
 */
@Slf4j
@Service
public class CatalogService {

    /** Caps the page a client can ask for, so one request cannot pull the whole library. */
    private static final int MAX_PAGE_SIZE = 100;

    /** Types shown on the home-video timeline. */
    private static final List<MediaType> TIMELINE_TYPES =
            List.of(MediaType.HOME_VIDEO, MediaType.PHOTO);

    private final MediaItemRepository items;
    private final PlaybackService playback;
    private final MediaPaths paths;
    private final SidecarLocator sidecars;

    public CatalogService(MediaItemRepository items,
                          PlaybackService playback,
                          MediaPaths paths,
                          SidecarLocator sidecars) {
        this.items = items;
        this.playback = playback;
        this.paths = paths;
        this.sidecars = sidecars;
    }

    /**
     * @param category optional {@link MediaType} chip value; null or {@code all} for everything
     * @param query    optional case-insensitive substring match on title
     * @param genre    optional exact genre match
     * @param sort     one of {@code title}, {@code added}, {@code year}, {@code rating}
     * @param unwatched restrict to items this profile has not finished
     */
    @Transactional(readOnly = true)
    public ItemPageDto browse(Profile profile,
                              String category,
                              String query,
                              String genre,
                              String sort,
                              boolean unwatched,
                              Integer minHeight,
                              int page,
                              int size) {

        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        // Composed explicitly rather than chained: Specification.and rejects a null
        // argument, so an absent filter has to be skipped rather than passed through.
        Specification<MediaItem> spec = browsable();
        spec = and(spec, ofCategory(category));
        spec = and(spec, matchesTitle(query));
        spec = and(spec, hasGenre(genre));
        spec = and(spec, atLeastHeight(minHeight));

        Page<MediaItem> results = items.findAll(
                spec, PageRequest.of(Math.max(0, page), pageSize, sortOf(sort)));

        List<String> ids = results.getContent().stream().map(MediaItem::getId).toList();
        Map<String, PlaybackProgress> progress = playback.progressByItemId(profile, ids);

        List<ItemSummaryDto> summaries = results.getContent().stream()
                .filter(item -> !unwatched || isUnwatched(progress.get(item.getId())))
                .map(item -> toSummary(item, progress.get(item.getId())))
                .toList();

        return new ItemPageDto(
                summaries,
                results.getNumber(),
                results.getSize(),
                results.getTotalElements(),
                results.getTotalPages());
    }

    /** Recently-added rail on the client's home screen. */
    @Transactional(readOnly = true)
    public List<ItemSummaryDto> recentlyAdded(Profile profile, List<MediaType> types, int limit) {
        List<MediaType> requested = types == null || types.isEmpty()
                ? List.of(MediaType.FILM, MediaType.ANIME)
                : types;
        List<MediaItem> found = items
                .findByTypeInAndMissingFalseAndHiddenFalseOrderByAddedAtDesc(
                        requested, PageRequest.of(0, Math.min(Math.max(1, limit), 50)));
        Map<String, PlaybackProgress> progress = playback.progressByItemId(
                profile, found.stream().map(MediaItem::getId).toList());
        return found.stream().map(item -> toSummary(item, progress.get(item.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public ItemDetailDto detail(Profile profile, String itemId) {
        MediaItem item = require(itemId);
        PlaybackProgress progress = playback.progressByItemId(profile, List.of(itemId)).get(itemId);

        return new ItemDetailDto(
                item.getId(),
                item.getType().name(),
                item.getLibraryName(),
                item.getTitle(),
                item.getOriginalTitle(),
                item.getYear(),
                item.getPlot(),
                item.getTagline(),
                item.getRuntimeMinutes(),
                item.getRating(),
                item.getCertification(),
                item.getGenres(),
                item.getDirectors(),
                item.getCastMembers(),
                item.getStudio(),
                item.getQuality(),
                item.getTmdbId(),
                item.getImdbId(),
                item.getArtist(),
                item.getAlbum(),
                item.getTrackNumber(),
                item.getCapturedAt() == null ? null : item.getCapturedAt().toString(),
                item.getPlace(),
                item.getPeople(),
                item.getFileSize(),
                item.getFileName(),
                item.hasPoster(),
                item.hasBackdrop(),
                MediaInfoDto.from(item.getMediaInfo()),
                subtitleTracks(item),
                audioTracks(item),
                progress == null ? null : (int) progress.getPositionSeconds(),
                progress != null && progress.isWatched());
    }

    /** Library header counts, per-category breakdown and the genre facet list. */
    @Transactional(readOnly = true)
    public LibrarySummaryDto summary() {
        Map<MediaType, long[]> byType = new LinkedHashMap<>();
        for (Object[] row : items.countAndBytesByType()) {
            MediaType type = (MediaType) row[0];
            long count = ((Number) row[1]).longValue();
            long bytes = ((Number) row[2]).longValue();
            byType.put(type, new long[]{count, bytes});
        }

        List<CategoryDto> categories = new ArrayList<>();
        for (MediaType type : MediaType.values()) {
            long[] stats = byType.getOrDefault(type, new long[]{0L, 0L});
            categories.add(CategoryDto.from(type, stats[0], stats[1]));
        }

        return new LibrarySummaryDto(
                items.countByMissingFalseAndHiddenFalse(),
                items.totalBytes(),
                categories,
                items.findDistinctGenres());
    }

    /**
     * The home-video timeline, grouped by month, person or place.
     *
     * <p>Grouped in memory rather than by SQL: the timeline is bounded by what the
     * client renders, and person grouping means one item appears under each of its
     * tagged people, which a {@code group by} cannot express.
     */
    @Transactional(readOnly = true)
    public TimelineDto timeline(Profile profile, String groupBy, int page, int size) {
        String mode = groupBy == null ? "date" : groupBy.toLowerCase(Locale.ROOT);

        // Items are paged and then grouped, rather than grouping the whole library and
        // paging the groups. A photo library is the one place this catalog can hold tens
        // of thousands of rows, and a month is an unbounded group — so the page is the
        // unit that can actually be bounded. Groups may therefore span pages; the client
        // merges by the stable group key as it scrolls, which is what a timeline UI does
        // anyway.
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<MediaItem> pageOfItems = items
                .findByTypeInAndMissingFalseAndHiddenFalseOrderByCapturedAtDesc(
                        TIMELINE_TYPES, PageRequest.of(Math.max(0, page), pageSize));
        List<MediaItem> found = pageOfItems.getContent();

        Map<String, PlaybackProgress> progress = playback.progressByItemId(
                profile, found.stream().map(MediaItem::getId).toList());

        Map<String, List<ItemSummaryDto>> grouped = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();

        for (MediaItem item : found) {
            ItemSummaryDto summary = toSummary(item, progress.get(item.getId()));
            switch (mode) {
                case "person" -> {
                    if (item.getPeople().isEmpty()) {
                        addTo(grouped, labels, "untagged", "UNTAGGED", summary);
                    } else {
                        for (String person : item.getPeople()) {
                            addTo(grouped, labels, person.toLowerCase(Locale.ROOT),
                                    person.toUpperCase(Locale.ROOT), summary);
                        }
                    }
                }
                case "place" -> {
                    String place = item.getPlace() == null || item.getPlace().isBlank()
                            ? "unknown"
                            : item.getPlace();
                    addTo(grouped, labels, place.toLowerCase(Locale.ROOT),
                            place.toUpperCase(Locale.ROOT), summary);
                }
                case "date" -> {
                    MonthKey key = monthKeyOf(item.getCapturedAt());
                    addTo(grouped, labels, key.key(), key.label(), summary);
                }
                default -> throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Unknown grouping '" + groupBy + "' (expected date, person or place)");
            }
        }

        List<TimelineGroupDto> groups = new ArrayList<>();
        grouped.forEach((key, list) ->
                groups.add(new TimelineGroupDto(key, labels.get(key), list.size(), list)));
        if (mode.equals("date")) {
            // Newest month first; the undated bucket sorts last on its sentinel key.
            groups.sort(Comparator.comparing(TimelineGroupDto::key).reversed());
        }

        return new TimelineDto(
                mode,
                groups,
                items.countByTypeInAndCapturedAtIsNullAndMissingFalseAndHiddenFalse(TIMELINE_TYPES),
                pageOfItems.getNumber(),
                pageOfItems.getSize(),
                pageOfItems.getTotalElements(),
                pageOfItems.getTotalPages(),
                pageOfItems.hasNext());
    }

    private static void addTo(Map<String, List<ItemSummaryDto>> grouped,
                              Map<String, String> labels,
                              String key,
                              String label,
                              ItemSummaryDto summary) {
        grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(summary);
        labels.putIfAbsent(key, label);
    }

    /** {@code DEC / 2024} in the client's left gutter. */
    private static MonthKey monthKeyOf(Instant capturedAt) {
        if (capturedAt == null) {
            // Sorts below every real month, so undated clips land at the end.
            return new MonthKey("0000-00", "NO DATE");
        }
        var date = capturedAt.atZone(ZoneId.systemDefault()).toLocalDate();
        String label = date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                .toUpperCase(Locale.ENGLISH) + " / " + date.getYear();
        return new MonthKey(String.format("%04d-%02d", date.getYear(), date.getMonthValue()), label);
    }

    private record MonthKey(String key, String label) {}

    @Transactional(readOnly = true)
    public List<String> genres() {
        return items.findDistinctGenres();
    }

    @Transactional(readOnly = true)
    public List<String> people() {
        return items.findDistinctPeople();
    }

    @Transactional(readOnly = true)
    public long count() {
        return items.countByMissingFalseAndHiddenFalse();
    }

    /** @throws ApiException 404 if unknown, 410 if the file has gone from disk */
    @Transactional(readOnly = true)
    public MediaItem require(String itemId) {
        MediaItem item = items.findById(itemId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Item not found"));
        if (item.isMissing()) {
            // Distinguished from 404 so the client can say "disk offline" rather than
            // "no such title" — the row is still there, the bytes are not.
            throw new ApiException(HttpStatus.GONE, "File is no longer on disk");
        }
        return item;
    }

    /** Embedded audio tracks, so the player's audio chip can name them. */
    public List<AudioTrackDto> audioTracks(MediaItem item) {
        MediaInfo info = item.getMediaInfo();
        if (info == null || info.getAudioTracks() == null) {
            return List.of();
        }
        List<AudioTrackDto> tracks = new ArrayList<>();
        for (String entry : info.getAudioTracks().split(";")) {
            String[] parts = entry.split(":", -1);
            if (parts.length < 3) {
                continue;
            }
            try {
                tracks.add(new AudioTrackDto(
                        Integer.parseInt(parts[0]),
                        parts[1],
                        parts[2],
                        parts.length > 3 && !parts[3].isBlank() ? parts[3] : null));
            } catch (NumberFormatException ignored) {
                // A malformed cached entry; skip rather than fail the detail view.
            }
        }
        return tracks;
    }

    /**
     * External subtitle files beside the item, plus whatever the container holds.
     *
     * <p>Indexes are positional and stable only while the folder contents are: the
     * client fetches by the index it was given in this response.
     */
    public List<SubtitleTrackDto> subtitleTracks(MediaItem item) {
        List<SubtitleTrackDto> tracks = new ArrayList<>();
        int index = 0;

        for (SidecarLocator.SubtitleTrack track : externalSubtitles(item)) {
            tracks.add(new SubtitleTrackDto(index++, track.language(), track.format(),
                    track.forced(), track.hearingImpaired(), false));
        }

        // Embedded tracks are reported so the app can list them, but serving one needs
        // an ffmpeg extraction pass that is not wired up yet.
        MediaInfo info = item.getMediaInfo();
        if (info != null && info.getEmbeddedSubtitles() != null) {
            for (String entry : info.getEmbeddedSubtitles().split(";")) {
                String[] parts = entry.split(":");
                if (parts.length < 3) {
                    continue;
                }
                tracks.add(new SubtitleTrackDto(index++, parts[2], parts[1], false, false, true));
            }
        }
        return tracks;
    }

    /** Resolves the media file, then lists the subtitle files sitting next to it. */
    public List<SidecarLocator.SubtitleTrack> externalSubtitles(MediaItem item) {
        if (!paths.isConfigured()) {
            return List.of();
        }
        try {
            Path file = paths.requireWithinRoots(item.getFilePath());
            return sidecars.findSubtitles(file);
        } catch (ApiException ex) {
            // A detail view must still render when the disk is unavailable.
            log.debug("Could not list subtitles for {}: {}", item.getId(), ex.getMessage());
            return List.of();
        }
    }

    private ItemSummaryDto toSummary(MediaItem item, PlaybackProgress progress) {
        return ItemSummaryDto.from(
                item,
                progress == null ? null : (int) progress.getPositionSeconds(),
                progress != null && progress.isWatched(),
                progress == null ? null : progress.percentComplete());
    }

    private static boolean isUnwatched(PlaybackProgress progress) {
        return progress == null || !progress.isWatched();
    }

    // --- specifications ---

    private static Specification<MediaItem> and(Specification<MediaItem> base,
                                                Specification<MediaItem> extra) {
        return extra == null ? base : base.and(extra);
    }

    /** Missing and deliberately-hidden items are indexed but never browsable. */
    private static Specification<MediaItem> browsable() {
        return (root, query, cb) -> cb.and(
                cb.isFalse(root.get("missing")),
                cb.isFalse(root.get("hidden")));
    }

    private static Specification<MediaItem> ofCategory(String category) {
        if (category == null || category.isBlank() || category.equalsIgnoreCase("all")) {
            return null;
        }
        MediaType type = MediaType.parse(category).orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST, "Unknown category '" + category + "'"));
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    private static Specification<MediaItem> matchesTitle(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        String pattern = "%" + rawQuery.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern),
                cb.like(cb.lower(root.get("sortTitle")), pattern),
                cb.like(cb.lower(root.get("fileName")), pattern));
    }

    private static Specification<MediaItem> hasGenre(String genre) {
        if (genre == null || genre.isBlank()) {
            return null;
        }
        return (root, query, cb) -> {
            // A join onto the element collection multiplies rows, so the count query
            // Spring Data derives for paging needs the same distinct treatment.
            if (query != null) {
                query.distinct(true);
            }
            return cb.equal(cb.lower(root.join("genres", JoinType.INNER)),
                    genre.toLowerCase(Locale.ROOT));
        };
    }

    /** Backs the client's {@code 4K ONLY} filter chip. */
    private static Specification<MediaItem> atLeastHeight(Integer minHeight) {
        if (minHeight == null || minHeight <= 0) {
            return null;
        }
        return (root, query, cb) ->
                cb.greaterThanOrEqualTo(root.get("mediaInfo").get("height"), minHeight);
    }

    private static Sort sortOf(String sort) {
        String key = sort == null ? "title" : sort.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "added" -> Sort.by(Sort.Direction.DESC, "addedAt");
            case "captured" -> Sort.by(Sort.Order.desc("capturedAt").nullsLast());
            case "year" -> Sort.by(Sort.Order.desc("year").nullsLast(), Sort.Order.asc("sortTitle"));
            case "rating" -> Sort.by(Sort.Order.desc("rating").nullsLast(),
                    Sort.Order.asc("sortTitle"));
            case "title" -> Sort.by(Sort.Direction.ASC, "sortTitle");
            default -> throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Unknown sort '" + sort + "' (expected title, added, captured, year or rating)");
        };
    }

    /** Exposed for callers that need the entity without the 410-on-missing behaviour. */
    @Transactional(readOnly = true)
    public Optional<MediaItem> find(String itemId) {
        return items.findById(itemId);
    }

    /**
     * Counts how a title was played, for the admin panel's "always transcodes" note.
     *
     * <p>One small write per playback start. Worth it: whether a file is a recurring
     * CPU cost cannot be derived from its codecs, only from what devices have actually
     * done with it.
     */
    @Transactional
    public void recordPlaybackDecision(String itemId, boolean directPlay) {
        items.findById(itemId).ifPresent(item -> {
            if (directPlay) {
                item.setDirectPlayCount(item.getDirectPlayCount() + 1);
            } else {
                item.setTranscodeCount(item.getTranscodeCount() + 1);
            }
            item.setLastPlayedAt(Instant.now());
            items.save(item);
        });
    }
}
