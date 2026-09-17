package com.example.kido.media.search;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.dto.CatalogDtos.ItemPageDto;
import com.example.kido.media.dto.SearchDtos.SearchResultDto;
import com.example.kido.media.dto.SearchDtos.SearchTermDto;
import com.example.kido.media.query.CatalogQuery;
import com.example.kido.media.search.SearchQueryParser.Parsed;
import com.example.kido.media.search.SearchQueryParser.Term;
import com.example.kido.media.search.ai.QueryTranslator;
import com.example.kido.profile.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * Search by sentence: "Tamil films under 2 hours rated over 8".
 *
 * <p>Two parsers, in order. {@link SearchQueryParser} is a grammar over this library's
 * own vocabulary — free, instant, offline, and right about most of what people type. A
 * {@link QueryTranslator} is asked only when that grammar reads nothing at all, which
 * keeps the model off the common path entirely: no latency, no cost, and no dependency
 * on somebody else's service for a search that was already working.
 *
 * <p>Both produce a {@link CatalogQuery} and nothing else. That is the containment the
 * whole design rests on — whatever writes the query, the query is all it can say, so the
 * model cannot widen what a search is able to ask for.
 */
@Slf4j
@Service
public class SearchService {

    private final MediaItemRepository items;
    private final CatalogService catalog;

    /**
     * Absent unless {@code app.media.ai.enabled} is on — the translator is a conditional
     * bean, so this is how a service compiled against it survives a server that never
     * had one.
     */
    private final ObjectProvider<QueryTranslator> translator;

    public SearchService(MediaItemRepository items,
                         CatalogService catalog,
                         ObjectProvider<QueryTranslator> translator) {
        this.items = items;
        this.catalog = catalog;
        this.translator = translator;
    }

    @Transactional(readOnly = true)
    public SearchResultDto search(Profile profile, String text, int page, int size) {
        SearchVocabulary vocabulary = vocabulary();
        Parsed parsed = SearchQueryParser.parse(text, vocabulary);

        CatalogQuery query = parsed.query();
        List<SearchTermDto> terms = parsed.terms().stream().map(SearchService::toDto).toList();
        String interpretedBy = "rules";

        if (parsed.understoodNothing()) {
            Optional<CatalogQuery> translated = translate(text, vocabulary);
            if (translated.isPresent()) {
                query = translated.get();
                terms = List.of();
                interpretedBy = "model";
            }
        }

        ItemPageDto results = catalog.search(profile, query, page, size);
        return new SearchResultDto(text, query, terms, parsed.understoodNothing(),
                interpretedBy, results);
    }

    /**
     * Asks the model, if there is one and it is willing.
     *
     * <p>Every path out of here that is not a usable query is empty, and empty means the
     * deterministic parse stands. Nothing about the model being off, unreachable, slow or
     * wrong can make search worse than it was before it existed.
     *
     * <p>The guard is here rather than inside an implementation on purpose. This is the
     * seam every translator passes through, so the guarantee holds for whatever is
     * plugged into it, not only for the one that remembers to catch its own exceptions.
     *
     * <p>Validation is inside the guard for the same reason, and it is why a model
     * answer the catalog would refuse — a crossed range, an unknown sort — falls back
     * rather than becoming a 400. The person typed a sentence; telling them their year
     * range is impossible would be blaming them for something a model did.
     */
    private Optional<CatalogQuery> translate(String text, SearchVocabulary vocabulary) {
        QueryTranslator available = translator.getIfAvailable();
        if (available == null || !available.isAvailable()) {
            return Optional.empty();
        }
        try {
            Optional<CatalogQuery> translated = available.translate(text, vocabulary)
                    .map(CatalogQuery::validated);
            translated.ifPresent(query ->
                    log.debug("Model read '{}' where the rules could not", text));
            return translated;
        } catch (RuntimeException e) {
            log.warn("Model could not be used for '{}': {}", text, e.toString());
            return Optional.empty();
        }
    }

    /**
     * What this library can be searched for.
     *
     * <p>Three cheap distinct-queries per search. Worth not caching: a scan can change
     * any of them, and a search that could not find a film added an hour ago because the
     * vocabulary was stale would be a much worse bug than three indexed reads.
     */
    @Transactional(readOnly = true)
    public SearchVocabulary vocabulary() {
        return SearchVocabulary.of(
                items.findDistinctGenres(),
                items.findDistinctPeople(),
                items.findDistinctLanguages());
    }

    private static SearchTermDto toDto(Term term) {
        return new SearchTermDto(term.field(), term.value(), term.label(), term.matched());
    }

    /**
     * The parse alone, for a client that wants to show chips before running anything.
     *
     * <p>Rules only, deliberately: this fires on every keystroke a search box sends, and
     * asking a model per keystroke would be both slow and expensive for a preview.
     */
    @Transactional(readOnly = true)
    public List<SearchTermDto> interpret(String text) {
        return SearchQueryParser.parse(text, vocabulary()).terms().stream()
                .map(SearchService::toDto)
                .toList();
    }
}
