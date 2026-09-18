package com.example.kido.media.collection;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.query.CatalogQuery.Range;
import com.example.kido.media.query.CatalogQuery.WatchedBy;

/**
 * The collections every library gets without anyone building them.
 *
 * <p>Code rather than seeded rows, for two reasons. A fresh install has a working home
 * screen before anything has been written to the database, and a later version can
 * improve a definition — widen "hidden gems", say — without a data migration chasing
 * every existing household. The only thing the database remembers about these is
 * whether someone pinned or renamed one.
 *
 * <p>Each is a query, so each is only as good as the facets it can ask about. That is
 * why {@link #NEVER_WATCHED} asks {@link WatchedBy#NOBODY} rather than about the
 * requesting profile: a household collection that hid whatever one person had seen
 * would be a different list on every phone in the house.
 */
public enum BuiltinCollection {

    /**
     * Nobody here has finished it. The most useful one on a shared library, and the
     * reason the query object learned to ask about the household rather than the
     * profile.
     */
    NEVER_WATCHED("never-watched", "Never watched", "🌱",
            () -> CatalogQuery.builder()
                    .types(Defaults.VIDEO)
                    .watched(WatchedBy.NOBODY)
                    .sort("added")
                    .build()),

    /** For a weeknight. The length people actually filter on. */
    UNDER_TWO_HOURS("under-two-hours", "Under 2 hours", "⏱️",
            () -> CatalogQuery.builder()
                    .types(Defaults.VIDEO)
                    .runtimeMinutes(Range.atMost(120))
                    .sort("rating")
                    .build()),

    /** What the disk is actually for. */
    FOUR_K("four-k", "4K", "🖥️",
            () -> CatalogQuery.builder()
                    .types(Defaults.VIDEO)
                    .minHeight(2160)
                    .sort("added")
                    .build()),

    HIGHLY_RATED("highly-rated", "Highly rated", "⭐",
            () -> CatalogQuery.builder()
                    .types(Defaults.VIDEO)
                    .rating(Range.atLeast(8.0))
                    .sort("rating")
                    .build()),

    /**
     * Well reviewed and never played. Distinct from {@link #NEVER_WATCHED}, which is
     * mostly whatever landed on the disk most recently — this is the good thing that
     * has been sitting there for a year.
     */
    HIDDEN_GEMS("hidden-gems", "Hidden gems", "💎",
            () -> CatalogQuery.builder()
                    .types(Defaults.VIDEO)
                    .rating(Range.atLeast(7.5))
                    .watched(WatchedBy.NOBODY)
                    .sort("rating")
                    .build()),

    /** Released this year, by the server's clock. */
    THIS_YEAR("this-year", "New this year", "🗓️",
            () -> CatalogQuery.builder()
                    .types(Defaults.VIDEO)
                    .year(Range.atLeast(LocalDate.now(ZoneOffset.UTC).getYear()))
                    .sort("rating")
                    .build()),

    /** Home footage, which is the one category nobody else's library has. */
    OUR_OWN("our-own", "Ours", "🏡",
            () -> CatalogQuery.builder()
                    .types(Set.of(MediaType.HOME_VIDEO))
                    .sort("captured")
                    .build());

    /**
     * A holder, because an enum constant may not forward-reference a static field of
     * its own enum — even from inside a lambda that will not run until later.
     */
    private static final class Defaults {
        /** Poster tiles, not the camera roll — the same default the home screen uses. */
        static final Set<MediaType> VIDEO = Set.of(MediaType.FILM, MediaType.ANIME, MediaType.SERIES);

        private Defaults() {}
    }

    private final String key;
    private final String defaultName;
    private final String defaultIcon;
    private final Supplier<CatalogQuery> query;

    BuiltinCollection(String key, String defaultName, String defaultIcon,
                      Supplier<CatalogQuery> query) {
        this.key = key;
        this.defaultName = defaultName;
        this.defaultIcon = defaultIcon;
        this.query = query;
    }

    public String key() {
        return key;
    }

    public String defaultName() {
        return defaultName;
    }

    public String defaultIcon() {
        return defaultIcon;
    }

    /**
     * A fresh query each call, because {@link #THIS_YEAR} depends on today's date — a
     * definition cached at class-load would be wrong on the first of January.
     */
    public CatalogQuery query() {
        return query.get().validated();
    }

    public static Optional<BuiltinCollection> byKey(String key) {
        return Arrays.stream(values()).filter(c -> c.key.equals(key)).findFirst();
    }
}
