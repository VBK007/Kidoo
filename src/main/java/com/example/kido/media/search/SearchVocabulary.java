package com.example.kido.media.search;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.kido.media.metadata.Languages;

/**
 * The words this library can actually be searched for.
 *
 * <p>The hard part of understanding a sentence is usually resolving the entities in it —
 * deciding that "tamil" is a language and "rajinikanth" is a person. This server does not
 * have to work that out, because it already knows every genre, name and language on the
 * disk. A closed vocabulary is what makes a rule-based parser viable here where it would
 * not be in general.
 *
 * <p>It is also a guard. A parser that cannot invent a genre cannot produce a query for
 * one, so "action" resolves and "acton" simply does not match — which is a far better
 * failure than a search that silently returns nothing.
 *
 * <p>A value, passed in rather than looked up, so {@link SearchQueryParser} stays pure and
 * testable without a database.
 */
public record SearchVocabulary(
        /** Genres as stored, lowercased. */
        Set<String> genres,
        /** Tagged cast and crew, lowercased. */
        Set<String> people,
        /** Every spelling of a language the library holds, mapped to its ISO code. */
        Map<String, String> languages) {

    public static SearchVocabulary empty() {
        return new SearchVocabulary(Set.of(), Set.of(), Map.of());
    }

    /**
     * Builds the language index from the codes the library holds.
     *
     * <p>Both the code and the English name are accepted, because people type "tamil"
     * and clients send "ta", and either should find the same films.
     */
    public static SearchVocabulary of(List<String> genres, List<String> people,
                                      List<String> languageCodes) {
        Map<String, String> bySpelling = new LinkedHashMap<>();
        for (String code : languageCodes) {
            String lower = code.toLowerCase(Locale.ROOT);
            bySpelling.put(lower, lower);
            String name = Languages.displayName(code);
            if (name != null && !name.isBlank()) {
                bySpelling.put(name.toLowerCase(Locale.ROOT), lower);
            }
        }
        return new SearchVocabulary(lowercased(genres), lowercased(people),
                Map.copyOf(bySpelling));
    }

    /**
     * Phrases longest first.
     *
     * <p>Order is correctness, not tidiness: "science fiction" has to be tried before
     * "fiction", and "kristen stewart" before "kristen", or the longer name is left
     * half-consumed and its remainder becomes a title search.
     */
    public List<String> phrasesLongestFirst(Set<String> values) {
        return values.stream()
                .sorted(Comparator.comparingInt(String::length).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private static Set<String> lowercased(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
