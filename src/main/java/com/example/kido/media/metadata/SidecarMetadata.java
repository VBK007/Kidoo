package com.example.kido.media.metadata;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Descriptive fields recovered from disk for one movie, before they are merged onto
 * a {@link com.example.kido.media.catalog.Movie} row. A field left null simply means
 * the sidecar did not carry it.
 */
public record SidecarMetadata(
        String title,
        String originalTitle,
        String sortTitle,
        Integer year,
        String plot,
        String tagline,
        Integer runtimeMinutes,
        Double rating,
        String certification,
        Set<String> genres,
        List<String> directors,
        List<String> cast,
        String studio,
        LocalDate releaseDate,
        String tmdbId,
        String imdbId) {
}
