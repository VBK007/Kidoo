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
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.dto.CatalogDtos.CategoryDto;
import com.example.kido.media.dto.CatalogDtos.ItemDetailDto;
import com.example.kido.media.dto.CatalogDtos.ItemPageDto;
import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;
import com.example.kido.media.dto.CatalogDtos.LanguageDto;
import com.example.kido.media.dto.CatalogDtos.LibrarySummaryDto;
import com.example.kido.media.dto.CatalogDtos.MediaInfoDto;
import com.example.kido.media.dto.CatalogDtos.SubtitleTrackDto;
import com.example.kido.media.dto.CatalogDtos.TimelineDto;
import com.example.kido.media.dto.CatalogDtos.TimelineGroupDto;
import com.example.kido.media.dto.PlayerDtos.AudioTrackDto;
import com.example.kido.media.engagement.CommentService;
import com.example.kido.media.engagement.LikeService;
import com.example.kido.media.metadata.Languages;
import com.example.kido.media.metadata.SidecarLocator;
import com.example.kido.media.playback.PlaybackProgress;
import com.example.kido.media.playback.PlaybackService;
import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.query.CatalogQuery.WatchedBy;
import com.example.kido.media.query.CatalogQuerySpecs;
import com.example.kido.media.query.CatalogSort;
import com.example.kido.profile.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * Browsing, searching and detail lookups over the indexed library.
 *
 * <p>Every filtered listing goes through {@link CatalogQuery}: the browse endpoint
 * translates its chip parameters into one, and {@link CatalogQuerySpecs} turns that into
 * the database query. Nothing here composes predicates by hand any more, so a stored
 * collection and a typed URL reach the catalog by exactly the same path.
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
    private final LikeService likes;
    private final CommentService comments;
    private final MediaPaths paths;
    private final SidecarLocator sidecars;

    public CatalogService(MediaItemRepository items,
                          PlaybackService playback,
                          LikeService likes,
                          CommentService comments,
                          MediaPaths paths,
                          SidecarLocator sidecars) {
        this.items = items;
        this.playback = playback;
        this.likes = likes;
        this.comments = comments;
        this.paths = paths;
        this.sidecars = sidecars;
    }

    /**
     * The browse endpoint's parameters, translated to a {@link CatalogQuery}.
     *
     * <p>The chip values stay here rather than in the query object: {@code ours} and
     * {@code 4K ONLY} are this client's vocabulary, and a saved query should hold what
     * was meant — a type and a height — not the label a particular screen used for it.
     *
     * @param category optional {@link MediaType} chip value; null or {@code all} for everything
     * @param query    optional case-insensitive substring match on title
     * @param genre    optional exact genre match
     * @param person   optional exact match against a tagged cast/crew name
     * @param sort     a {@link CatalogSort} key
     * @param unwatched restrict to items this profile has not finished
     */
    @Transactional(readOnly = true)
    public ItemPageDto browse(Profile profile,
                              String category,
                              String query,
                              String genre,
                              String person,
                              String sort,
                              boolean unwatched,
                              Integer minHeight,
                              int page,
                              int size) {

        CatalogQuery built = CatalogQuery.builder()
                .types(typeOf(category))
                .titleContains(query)
                .genres(genre == null || genre.isBlank() ? null : Set.of(genre))
                .people(person == null || person.isBlank() ? null : Set.of(person))
                .minHeight(minHeight)
                .watched(unwatched ? WatchedBy.NOT_ME : WatchedBy.ANYONE)
                .sort(sort)
                .build();

        return search(profile, built, page, size);
    }

    /**
     * Runs a {@link CatalogQuery} and returns a page of tiles.
     *
     * <p>The one way to ask the catalog anything. Whatever composed the query — the
     * browse endpoint above, a stored collection, a parsed sentence — reaches the
     * database through here, so the filters, the paging and the totals behave the same
     * for all of them and are worth testing once.
     */
    @Transactional(readOnly = true)
    public ItemPageDto search(Profile profile, CatalogQuery query, int page, int size) {
        CatalogQuery validated = query.validated();
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        Page<MediaItem> results = items.findAll(
                CatalogQuerySpecs.toSpecification(validated, profile == null ? null : profile.getId()),
                PageRequest.of(Math.max(0, page), pageSize,
                        CatalogSort.of(validated.sort()).sort()));

        return new ItemPageDto(
                summarise(profile, results.getContent()),
                results.getNumber(),
                results.getSize(),
                results.getTotalElements(),
                results.getTotalPages());
    }

    /** Chip label to type. Unknown labels are a client bug, so they are a 400. */
    private static Set<MediaType> typeOf(String category) {
        if (category == null || category.isBlank() || category.equalsIgnoreCase("all")) {
            return null;
        }
        return Set.of(MediaType.parse(category).orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST, "Unknown category '" + category + "'")));
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
        return summarise(profile, found);
    }

    /**
     * Turns a list of entities into tiles, resolving watch progress and this profile's
     * likes in one query each rather than one per item.
     */
    @Transactional(readOnly = true)
    public List<ItemSummaryDto> summarise(Profile profile, List<MediaItem> found) {
        List<String> ids = found.stream().map(MediaItem::getId).toList();
        Map<String, PlaybackProgress> progress = playback.progressByItemId(profile, ids);
        Set<String> liked = likes.likedItemIds(profile, ids);
        return found.stream()
                .map(item -> toSummary(item, progress.get(item.getId()), liked))
                .toList();
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
                item.getLanguages(),
                item.getPrimaryLanguage(),
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
                progress != null && progress.isWatched(),
                item.playCount(),
                item.getLikeCount(),
                !likes.likedItemIds(profile, List.of(itemId)).isEmpty(),
                comments.countFor(itemId));
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

        List<String> ids = found.stream().map(MediaItem::getId).toList();
        Map<String, PlaybackProgress> progress = playback.progressByItemId(profile, ids);
        Set<String> liked = likes.likedItemIds(profile, ids);

        Map<String, List<ItemSummaryDto>> grouped = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();

        for (MediaItem item : found) {
            ItemSummaryDto summary = toSummary(item, progress.get(item.getId()), liked);
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

    /**
     * Languages in the library, each with its English name.
     *
     * <p>The name is resolved here rather than on the client because the mapping from
     * {@code ta} to "Tamil" is an ISO table, and shipping one to every client so each
     * can render the same chip would be three copies of it.
     */
    @Transactional(readOnly = true)
    public List<LanguageDto> languages() {
        return items.findDistinctLanguages().stream()
                .map(code -> new LanguageDto(code, Languages.displayName(code)))
                .toList();
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

    private ItemSummaryDto toSummary(MediaItem item,
                                     PlaybackProgress progress,
                                     Set<String> likedIds) {
        return ItemSummaryDto.from(
                item,
                progress == null ? null : (int) progress.getPositionSeconds(),
                progress != null && progress.isWatched(),
                progress == null ? null : progress.percentComplete(),
                likedIds.contains(item.getId()));
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
