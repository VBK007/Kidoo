package com.example.kido.media.search.ai;

import java.util.Optional;

import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.search.SearchVocabulary;

/**
 * Turns a sentence the grammar could not read into a {@link CatalogQuery}.
 *
 * <p>An interface with one real implementation, for two reasons that are not
 * abstraction for its own sake. The fallback has to be testable without a network call,
 * and — more to the point — the whole design rests on the model producing the same
 * object the rule-based parser produces and nothing else. Naming that contract makes it
 * checkable: everything downstream takes a {@code CatalogQuery}, so a translator cannot
 * widen what a search is able to ask for, whoever writes it.
 *
 * <p>Returning {@link Optional#empty()} is always allowed and always safe. It means the
 * caller keeps whatever the deterministic parse produced, which is what happens when
 * the feature is off, unconfigured, timed out, or simply wrong.
 */
public interface QueryTranslator {

    /**
     * @param text       the sentence, as typed
     * @param vocabulary what this library can be searched for — given to the model so it
     *                   cannot invent a genre the catalog has never heard of
     * @return the query, or empty to fall back
     */
    Optional<CatalogQuery> translate(String text, SearchVocabulary vocabulary);

    /** False when nothing would be attempted, so callers can skip the work of asking. */
    boolean isAvailable();
}
