package com.example.kido.media.collection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.metadata.Languages;
import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.query.CatalogQuery.Range;

/**
 * Collections nobody had to create: the groupings a library already contains.
 *
 * <p>A household should not have to build "Tamil films" by hand when every Tamil film on
 * the disk already says so. So the facets are tallied and any group big enough to be
 * worth a row becomes a collection — which, since a collection is just a named query,
 * costs a name and a {@link CatalogQuery} and no storage at all.
 *
 * <p>Computed on request rather than materialised by a scan. The alternative would be a
 * table that is wrong between the moment a file is indexed and the moment the job next
 * runs, in exchange for saving four grouped queries over a table of a few thousand rows.
 * Staleness is the worse bargain.
 *
 * <p>Nothing here is a guess. Every group comes from a facet the scanner read or the
 * probe reported — see the note below {@link #decadeName} for the one grouping that
 * would have to guess, and so is absent.
 */
@Service
public class DiscoveredCollections {

    /**
     * Below this a "collection" is just a film with a label on it. Three is the point at
     * which a row on the home screen says something; two titles is a coincidence.
     */
    private static final int MIN_MEMBERS = 3;

    /** Per facet kind, so a library with two hundred actors does not produce two hundred rows. */
    private static final int MAX_PER_KIND = 12;

    /** A decade needs more members than a genre before it means anything. */
    private static final int MIN_DECADE_MEMBERS = 5;

    private static final List<MediaType> VIDEO_TYPES =
            List.of(MediaType.FILM, MediaType.ANIME);

    private final MediaItemRepository items;

    public DiscoveredCollections(MediaItemRepository items) {
        this.items = items;
    }

    /**
     * One discovered collection: a name, and the query that produces it.
     *
     * @param key       stable and lowercased, e.g. {@code genre:action} — the facet's
     *                  stored casing is display, not identity, and a client should not
     *                  have to reproduce it to open the collection again
     * @param itemCount how many titles it holds, already known from the tally, so listing
     *                  these costs no count query each
     */
    public record Discovered(String key, String name, String icon, int itemCount,
                             CatalogQuery query) {}

    @Transactional(readOnly = true)
    public List<Discovered> all() {
        List<Discovered> found = new ArrayList<>();
        found.addAll(byGenre());
        found.addAll(byLanguage());
        found.addAll(byPerson());
        found.addAll(byDecade());
        return found;
    }

    /** Looks one up by the key {@link #all()} gave it. */
    @Transactional(readOnly = true)
    public Optional<Discovered> byKey(String key) {
        String wanted = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return all().stream().filter(d -> d.key().equals(wanted)).findFirst();
    }

    private List<Discovered> byGenre() {
        return tally(items.countByGenre(VIDEO_TYPES), MIN_MEMBERS, (value, count) ->
                new Discovered("genre:" + key(value), titleCase(value), "🎬", count,
                        CatalogQuery.builder()
                                .types(Set.copyOf(VIDEO_TYPES))
                                .genres(Set.of(value))
                                .sort("rating")
                                .build()
                                .validated()));
    }

    private List<Discovered> byLanguage() {
        return tally(items.countByLanguage(VIDEO_TYPES), MIN_MEMBERS, (value, count) ->
                new Discovered("language:" + key(value), Languages.displayName(value),
                        "🗣️", count,
                        CatalogQuery.builder()
                                .types(Set.copyOf(VIDEO_TYPES))
                                .languages(Set.of(value))
                                // The primary track, so this is "Tamil films" and not
                                // "films that happen to carry a Tamil dub".
                                .primaryLanguageOnly(true)
                                .sort("rating")
                                .build()
                                .validated()));
    }

    /**
     * Cast and crew the library keeps coming back to.
     *
     * <p>Only people the scanner actually tagged, which on a library matched from
     * filenames is often nobody — an empty result here is a metadata gap, not a bug.
     */
    private List<Discovered> byPerson() {
        return tally(items.countByPerson(VIDEO_TYPES), MIN_MEMBERS, (value, count) ->
                new Discovered("person:" + key(value), titleCase(value), "🎭", count,
                        CatalogQuery.builder()
                                .types(Set.copyOf(VIDEO_TYPES))
                                .people(Set.of(value))
                                .sort("rating")
                                .build()
                                .validated()));
    }

    /**
     * Decades, bucketed here rather than in SQL because integer division is one of the
     * things H2 and PostgreSQL disagree about, and a library spans a century at most.
     */
    private List<Discovered> byDecade() {
        Map<Integer, Integer> perDecade = new LinkedHashMap<>();
        for (Object[] row : items.countByYear(VIDEO_TYPES)) {
            if (row[0] == null) {
                continue;
            }
            int decade = (((Number) row[0]).intValue() / 10) * 10;
            perDecade.merge(decade, ((Number) row[1]).intValue(), Integer::sum);
        }

        List<Discovered> found = new ArrayList<>();
        perDecade.entrySet().stream()
                .filter(entry -> entry.getValue() >= MIN_DECADE_MEMBERS)
                .sorted(Map.Entry.<Integer, Integer>comparingByKey().reversed())
                .limit(MAX_PER_KIND)
                .forEach(entry -> {
                    int decade = entry.getKey();
                    found.add(new Discovered("decade:" + decade, decadeName(decade),
                            "📼", entry.getValue(),
                            CatalogQuery.builder()
                                    .types(Set.copyOf(VIDEO_TYPES))
                                    .year(Range.between(decade, decade + 9))
                                    .sort("rating")
                                    .build()
                                    .validated()));
                });
        return found;
    }

    /**
     * "The 90s" but "The 2000s": the two-digit form is only unambiguous for the century
     * people still shorten that way, and nobody calls the 2010s "the 10s".
     */
    private static String decadeName(int decade) {
        return decade >= 2000
                ? "The " + decade + "s"
                : "The " + (decade % 100) + "s";
    }

    // There is no franchise grouping here, and the reason is worth writing down.
    //
    // Grouping "Avengers: Endgame" with "Avengers: Infinity War" means either a franchise
    // id from a metadata provider or a guess at the title. This server has no provider —
    // tmdbId is read out of an NFO sidecar and there is no client to ask about
    // collections — so it would have to be the guess, and a guess loose enough to catch
    // "The Godfather Part II" also groups "Kaithi" with "Kaithi (2019) 1080p".
    //
    // Every grouping above comes from a facet something actually read. One that came from
    // a hunch would make the whole list less trustworthy, and a wrong collection is worse
    // than a missing one because it looks deliberate.

    private interface Builder {
        Discovered build(String value, int count);
    }

    /** Applies the floor and the cap to one facet's tally. */
    private static List<Discovered> tally(List<Object[]> rows, int minMembers, Builder builder) {
        List<Discovered> found = new ArrayList<>();
        for (Object[] row : rows) {
            if (row[0] == null) {
                continue;
            }
            int count = ((Number) row[1]).intValue();
            if (count < minMembers) {
                // Rows arrive ordered by count, so the first below the floor means every
                // one after it is too.
                break;
            }
            found.add(builder.build((String) row[0], count));
            if (found.size() >= MAX_PER_KIND) {
                break;
            }
        }
        return found;
    }

    /** Identity, as opposed to display: a facet's stored casing must not be part of its key. */
    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /** Facet values are stored as the scanner wrote them; display them consistently. */
    private static String titleCase(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean startOfWord = true;
        for (char c : value.toCharArray()) {
            out.append(startOfWord ? Character.toUpperCase(c) : c);
            startOfWord = c == ' ' || c == '-';
        }
        return out.toString();
    }
}
