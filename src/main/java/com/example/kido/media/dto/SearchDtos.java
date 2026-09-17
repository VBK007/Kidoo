package com.example.kido.media.dto;

import java.util.List;

import com.example.kido.media.dto.CatalogDtos.ItemPageDto;
import com.example.kido.media.query.CatalogQuery;

/**
 * Search-by-sentence on the wire.
 *
 * <p>The parse comes back alongside the results, always. A search box that silently
 * reinterprets what somebody typed is one they cannot correct — they can only rephrase
 * and hope. Returning the terms lets a client draw them as removable chips, so a wrong
 * reading is fixed by tapping rather than by guessing at the parser.
 */
public final class SearchDtos {

    private SearchDtos() {}

    /**
     * One thing the server understood.
     *
     * @param field   the filter it set: {@code genre}, {@code language}, {@code person},
     *                {@code runtime}, {@code rating}, {@code year}, {@code minHeight},
     *                {@code watched}, {@code liked}, {@code sort}, {@code type} or
     *                {@code title}
     * @param label   how to print it on a chip, e.g. {@code Under 2 hours}
     * @param matched the words it was read from, so a person can see what was consumed
     */
    public record SearchTermDto(String field, String value, String label, String matched) {}

    /**
     * @param query          what actually ran — the same object the browse endpoint
     *                       builds, so a client can edit it and re-run it directly
     * @param understoodNothing true when no rule matched — the case the model-backed
     *                       fallback is asked to take over
     * @param interpretedBy  {@code rules} or {@code model}: which parser produced the
     *                       query that ran. A client drawing chips should trust
     *                       {@code terms} only for {@code rules} — the model answers
     *                       with a whole query rather than a span-by-span reading of
     *                       the sentence, so there is nothing to attribute to words
     */
    public record SearchResultDto(
            String query_text,
            CatalogQuery query,
            List<SearchTermDto> terms,
            boolean understoodNothing,
            String interpretedBy,
            ItemPageDto results) {}
}
