package com.example.kido.media.recommend;

import java.util.Map;
import java.util.Optional;

import com.example.kido.media.catalog.MediaItem;

/**
 * What one profile has shown it likes, as weights on the facets the catalog knows.
 *
 * <p>A value, computed from history rather than stored: see {@link TasteProfiler} for
 * why there is no table behind it.
 *
 * <p>Every weight is 0–1 within its own kind, so a genre weight and a language weight
 * can be compared. Nothing here is a probability — it is a ranking signal, and the only
 * property that matters is that a facet this profile keeps choosing outranks one it does
 * not.
 */
public record TasteProfile(
        String profileId,
        Map<Facet, Double> weights,
        /**
         * The title that did most to establish each facet, so a recommendation can say
         * "because you watched Kaithi" rather than "because you like Action" — the first
         * is checkable and the second is a horoscope.
         */
        Map<Facet, String> evidence,
        /** How many titles this was derived from. Zero means there is no history yet. */
        int signalCount) {

    /** The dimensions a taste is expressed in — the facets an item can be grouped by. */
    public enum FacetKind {
        GENRE,
        PERSON,
        LANGUAGE,
        /** The 1990s, the 2000s; broad enough to mean something, narrow enough to differ. */
        DECADE
    }

    /** One dimension and one value in it, e.g. {@code GENRE:action}. */
    public record Facet(FacetKind kind, String value) {}

    /** An empty taste — a profile that has watched nothing yet. */
    public static TasteProfile empty(String profileId) {
        return new TasteProfile(profileId, Map.of(), Map.of(), 0);
    }

    public boolean isEmpty() {
        return weights.isEmpty();
    }

    public double weightOf(Facet facet) {
        return weights.getOrDefault(facet, 0.0);
    }

    public Optional<String> evidenceFor(Facet facet) {
        return Optional.ofNullable(evidence.get(facet));
    }

    /**
     * Every facet an item carries, so scoring an item is a lookup per facet rather than
     * a rule per kind.
     *
     * <p>Facet values are lowercased, matching how {@link
     * com.example.kido.media.query.CatalogQuery} stores them — the same value has to
     * mean the same thing on both sides or a taste never matches anything.
     */
    public static java.util.Set<Facet> facetsOf(MediaItem item) {
        java.util.Set<Facet> facets = new java.util.LinkedHashSet<>();
        for (String genre : item.getGenres()) {
            facets.add(new Facet(FacetKind.GENRE, lower(genre)));
        }
        for (String person : item.getPeople()) {
            facets.add(new Facet(FacetKind.PERSON, lower(person)));
        }
        // The primary track only: a Hindi dub on a Tamil film says what the file carries,
        // not what anybody chose to watch.
        if (item.getPrimaryLanguage() != null) {
            facets.add(new Facet(FacetKind.LANGUAGE, lower(item.getPrimaryLanguage())));
        }
        if (item.getYear() != null) {
            facets.add(new Facet(FacetKind.DECADE, String.valueOf((item.getYear() / 10) * 10)));
        }
        return facets;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }
}
