package com.example.kido.media.search;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.media.catalog.CatalogService;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.dto.CatalogDtos.ItemPageDto;
import com.example.kido.media.dto.SearchDtos.SearchResultDto;
import com.example.kido.media.dto.SearchDtos.SearchTermDto;
import com.example.kido.media.search.SearchQueryParser.Parsed;
import com.example.kido.media.search.SearchQueryParser.Term;
import com.example.kido.profile.Profile;

import lombok.extern.slf4j.Slf4j;

/**
 * Search by sentence: "Tamil films under 2 hours rated over 8".
 *
 * <p>Loads the library's vocabulary, hands it and the text to {@link SearchQueryParser},
 * and runs whatever comes back through the ordinary catalog. The parse produces a
 * {@link com.example.kido.media.query.CatalogQuery} and nothing else, so a sentence can
 * only ask for things a URL could already have asked for.
 *
 * <p>That containment is the point, and it is what a model-backed parser will inherit:
 * whatever writes the query, the query is all it can say.
 */
@Slf4j
@Service
public class SearchService {

    private final MediaItemRepository items;
    private final CatalogService catalog;

    public SearchService(MediaItemRepository items, CatalogService catalog) {
        this.items = items;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public SearchResultDto search(Profile profile, String text, int page, int size) {
        Parsed parsed = SearchQueryParser.parse(text, vocabulary());
        ItemPageDto results = catalog.search(profile, parsed.query(), page, size);

        log.debug("Parsed '{}' into {} term(s)", text, parsed.terms().size());
        return new SearchResultDto(
                text,
                parsed.query(),
                parsed.terms().stream().map(SearchService::toDto).toList(),
                parsed.understoodNothing(),
                results);
    }

    /**
     * What this library can be searched for.
     *
     * <p>Three cheap distinct-queries per search. Worth not caching: a scan can change
     * any of them, and a search that cannot find a film added an hour ago because the
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

    /** The parse alone, for a client that wants to show chips before running anything. */
    @Transactional(readOnly = true)
    public List<SearchTermDto> interpret(String text) {
        return SearchQueryParser.parse(text, vocabulary()).terms().stream()
                .map(SearchService::toDto)
                .toList();
    }
}
