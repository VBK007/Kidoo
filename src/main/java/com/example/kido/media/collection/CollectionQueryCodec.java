package com.example.kido.media.collection;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.example.kido.common.ApiException;
import com.example.kido.media.query.CatalogQuery;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes the stored form of a {@link CatalogQuery}.
 *
 * <p>Its own {@link ObjectMapper} rather than the application's, deliberately. The web
 * mapper is configured for leniency towards clients — {@code
 * fail-on-null-for-primitives} is off so a partial offline sync merges — and a stored
 * query is not a client payload. Here an unknown field is a real signal: it means the
 * row was written by a newer build whose query object knew something this one does not,
 * and silently dropping it would run a *different, wider* search under the name the
 * owner gave it. Better to say the collection cannot be read than to quietly return the
 * wrong films.
 *
 * <p>Everything that comes out is {@link CatalogQuery#validated()}, so a row edited by
 * hand in the database is held to exactly the same rules as a URL.
 */
@Slf4j
@Component
public class CollectionQueryCodec {

    /**
     * Record components only.
     *
     * <p>Auto-detected getters are turned off because {@code CatalogQuery} and its
     * {@code Range} carry helper predicates — {@code isEmpty} and
     * {@code isImpossible} — that look exactly like properties to Jackson. Left on, they
     * are written into the stored JSON and then rejected on the way back by the strict
     * reader below: a query would save and never load. They are derived from the
     * components anyway, so there is nothing to store.
     */
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .changeDefaultVisibility(visibility -> visibility
                    .withGetterVisibility(Visibility.NONE)
                    .withIsGetterVisibility(Visibility.NONE)
                    .withFieldVisibility(Visibility.ANY))
            .build();

    public String write(CatalogQuery query) {
        try {
            return mapper.writeValueAsString(query.validated());
        } catch (JacksonException e) {
            // Nothing a caller can do about it, and it can only mean the record and the
            // mapper have got out of step — a bug, not bad input.
            log.error("Could not serialise a catalog query", e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not store the collection's query");
        }
    }

    /**
     * @param json  as stored on the row
     * @param label the collection's name, so a failure names the thing the person can
     *              actually see and delete rather than a row id
     */
    public CatalogQuery read(String json, String label) {
        if (json == null || json.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Collection '" + label + "' has no query stored");
        }
        try {
            return mapper.readValue(json, CatalogQuery.class).validated();
        } catch (JacksonException e) {
            log.warn("Unreadable query on collection '{}': {}", label, e.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Collection '" + label + "' was saved by a newer version and cannot "
                            + "be read by this one");
        }
    }
}
