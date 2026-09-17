package com.example.kido.media.assistant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.collection.CollectionService;
import com.example.kido.media.dto.CatalogDtos.ItemPageDto;
import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;
import com.example.kido.media.dto.CollectionDtos.CollectionDto;
import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.query.CatalogQuery.MatchMode;
import com.example.kido.media.query.CatalogQuery.Range;
import com.example.kido.media.query.CatalogQuery.WatchedBy;
import com.example.kido.media.recommend.TasteProfile;
import com.example.kido.media.recommend.TasteProfiler;
import com.example.kido.profile.Profile;
import com.example.kido.user.AppUser;

import lombok.extern.slf4j.Slf4j;

/**
 * What the assistant is allowed to do.
 *
 * <p>Two rules define this class, and both are enforced here rather than asked for in a
 * prompt.
 *
 * <p><b>Everything is read-only.</b> There is no tool to delete a title, purge a cache,
 * start a stream or change a setting. An assistant that can answer questions about a
 * library is useful; one that can act on it is a different feature with a different risk,
 * and it is not this one. A model cannot be talked into calling a tool that does not
 * exist.
 *
 * <p><b>Who is asking is not a parameter.</b> The profile and account come from the
 * request, never from the model, so "films I haven't seen" resolves against the caller
 * and there is no argument a model could fill in to ask about somebody else's viewing.
 *
 * <p>Answers are compact deliberately. A tool result is context the model pays for on
 * every subsequent turn, so these return the few fields an answer needs rather than the
 * DTOs the API serves.
 */
@Slf4j
@Service
public class AssistantTools {

    /** Enough to answer a question about; more is context nobody reads. */
    private static final int MAX_ROWS = 25;

    private final CatalogService catalog;
    private final MediaItemRepository items;
    private final TasteProfiler profiler;
    private final CollectionService collections;

    public AssistantTools(CatalogService catalog,
                          MediaItemRepository items,
                          TasteProfiler profiler,
                          CollectionService collections) {
        this.catalog = catalog;
        this.items = items;
        this.profiler = profiler;
        this.collections = collections;
    }

    /** Who is asking. Supplied by the endpoint; never by the model. */
    public record Caller(AppUser owner, Profile profile) {}

    /**
     * Searches the library.
     *
     * <p>Builds a {@link CatalogQuery} from the arguments, which is the same object the
     * browse endpoint, the collections and the search parser all produce — so the
     * assistant inherits every filter that already exists and can express nothing beyond
     * them.
     */
    @Transactional(readOnly = true)
    public String searchLibrary(Map<String, Object> arguments, Caller caller) {
        CatalogQuery.CatalogQueryBuilder query = CatalogQuery.builder()
                .titleContains(string(arguments, "title_contains"))
                .minHeight(integer(arguments, "min_height"))
                .sort(sortOrNull(string(arguments, "sort")));

        Set<MediaType> types = new LinkedHashSet<>();
        for (String type : strings(arguments, "types")) {
            MediaType.parse(type).ifPresent(types::add);
        }
        if (!types.isEmpty()) {
            query.types(types);
        }
        if (!strings(arguments, "genres").isEmpty()) {
            query.genres(lowercased(strings(arguments, "genres")));
            query.genreMatch("ALL".equalsIgnoreCase(string(arguments, "genre_match"))
                    ? MatchMode.ALL : MatchMode.ANY);
        }
        if (!strings(arguments, "people").isEmpty()) {
            query.people(lowercased(strings(arguments, "people")));
        }
        if (!strings(arguments, "languages").isEmpty()) {
            query.languages(lowercased(strings(arguments, "languages")));
            query.primaryLanguageOnly(true);
        }
        range(arguments, "min_year", "max_year").ifPresent(query::year);
        range(arguments, "min_runtime_minutes", "max_runtime_minutes")
                .ifPresent(query::runtimeMinutes);
        range(arguments, "min_rating", "max_rating").ifPresent(query::rating);
        watchedBy(string(arguments, "watched")).ifPresent(query::watched);
        if (arguments.get("liked") instanceof Boolean liked) {
            query.liked(liked);
        }

        int limit = Math.min(Math.max(1, integerOr(arguments, "limit", 10)), MAX_ROWS);
        ItemPageDto page = catalog.search(caller.profile(), query.build(), 0, limit);

        StringBuilder out = new StringBuilder();
        out.append(page.totalItems()).append(" title(s) match");
        if (page.totalItems() > page.items().size()) {
            out.append("; showing ").append(page.items().size());
        }
        out.append(".\n");
        for (ItemSummaryDto item : page.items()) {
            out.append(describe(item)).append('\n');
        }
        return out.toString().trim();
    }

    /**
     * Titles played more than a given number of times, across the household.
     *
     * <p>A separate tool because a play count is the sum of two columns and
     * {@link CatalogQuery} deliberately cannot sort or filter on it — the same reason
     * there is no {@code sort=views} on the browse endpoint.
     */
    @Transactional(readOnly = true)
    public String mostPlayed(Map<String, Object> arguments, Caller caller) {
        int minPlays = Math.max(1, integerOr(arguments, "min_plays", 1));
        int limit = Math.min(Math.max(1, integerOr(arguments, "limit", 10)), MAX_ROWS);

        List<MediaItem> played = items.findMostPlayed(
                List.of(MediaType.FILM, MediaType.ANIME), PageRequest.of(0, MAX_ROWS));

        List<String> rows = new ArrayList<>();
        for (MediaItem item : played) {
            long plays = item.playCount();
            if (plays < minPlays) {
                // Ordered by plays, so the first below the floor means every one after is.
                break;
            }
            rows.add("- %s (%d) — played %d time(s)".formatted(
                    item.getTitle(), item.getYear() == null ? 0 : item.getYear(), plays));
            if (rows.size() >= limit) {
                break;
            }
        }
        if (rows.isEmpty()) {
            return "Nothing has been played " + minPlays + " or more times.";
        }
        return String.join("\n", rows);
    }

    /**
     * What the server believes this profile likes, and what it read that from.
     *
     * <p>The caller's own, always: there is no argument for whose taste to fetch, so one
     * housemate cannot be asked about through another's session.
     */
    @Transactional(readOnly = true)
    public String tasteProfile(Map<String, Object> arguments, Caller caller) {
        TasteProfile taste = profiler.forProfile(caller.profile());
        if (taste.isEmpty()) {
            return "This profile has no viewing history yet, so there is nothing to go on "
                    + "beyond ratings and what was added recently.";
        }
        List<String> rows = new ArrayList<>();
        taste.weights().entrySet().stream()
                .sorted(Map.Entry.<TasteProfile.Facet, Double>comparingByValue().reversed())
                .limit(12)
                .forEach(entry -> rows.add("- %s %s: %.2f%s".formatted(
                        entry.getKey().kind().name().toLowerCase(Locale.ROOT),
                        entry.getKey().value(),
                        entry.getValue(),
                        taste.evidenceFor(entry.getKey())
                                .map(title -> " (from " + title + ")").orElse(""))));
        return "Derived from %d title(s):\n%s".formatted(taste.signalCount(),
                String.join("\n", rows));
    }

    /** The collections available, so the assistant can point at one rather than rebuild it. */
    @Transactional(readOnly = true)
    public String listCollections(Map<String, Object> arguments, Caller caller) {
        List<String> rows = new ArrayList<>();
        var all = collections.list(caller.owner(), caller.profile());
        for (List<CollectionDto> group : List.of(all.builtin(), all.custom(), all.discovered())) {
            for (CollectionDto collection : group) {
                if (collection.itemCount() > 0) {
                    rows.add("- %s (%d title(s))".formatted(
                            collection.name(), collection.itemCount()));
                }
            }
        }
        return rows.isEmpty() ? "No collections have anything in them." : String.join("\n", rows);
    }

    // --- argument reading ---
    //
    // Everything here is defensive. Tool arguments come from a model, so a missing field,
    // a string where a number belongs, or a null inside a list is an ordinary Tuesday
    // rather than a bug — and none of it should reach the catalog as an exception.

    private static String describe(ItemSummaryDto item) {
        StringBuilder out = new StringBuilder("- ").append(item.title());
        if (item.year() != null) {
            out.append(" (").append(item.year()).append(')');
        }
        if (item.runtimeMinutes() != null) {
            out.append(", ").append(item.runtimeMinutes()).append(" min");
        }
        if (item.rating() != null) {
            out.append(", rated ").append(item.rating());
        }
        if (item.watched()) {
            out.append(", watched");
        }
        return out.toString();
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static List<String> strings(Map<String, Object> arguments, String key) {
        if (!(arguments.get(key) instanceof List<?> raw)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object value : raw) {
            if (value instanceof String text && !text.isBlank()) {
                values.add(text);
            }
        }
        return values;
    }

    private static Set<String> lowercased(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        values.forEach(value -> out.add(value.trim().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static Integer integer(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int integerOr(Map<String, Object> arguments, String key, int fallback) {
        Integer value = integer(arguments, key);
        return value == null ? fallback : value;
    }

    private static Optional<Range> range(Map<String, Object> arguments, String minKey,
                                         String maxKey) {
        Double min = decimal(arguments, minKey);
        Double max = decimal(arguments, maxKey);
        if (min == null && max == null) {
            return Optional.empty();
        }
        return Optional.of(new Range(min, max));
    }

    private static Double decimal(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * An unusable ordering is dropped, not rejected.
     *
     * <p>The same treatment every other argument here gets, and for the same reason: a
     * model choosing a sort key that does not exist should cost the person nothing.
     * Failing the tool would turn "what should I watch" into an error over the ordering
     * of a list nobody had specified.
     */
    private static String sortOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return com.example.kido.media.query.CatalogSort.of(value).key();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Optional<WatchedBy> watchedBy(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            WatchedBy watched = WatchedBy.valueOf(value.trim().toUpperCase(Locale.ROOT));
            return watched == WatchedBy.ANYONE ? Optional.empty() : Optional.of(watched);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
