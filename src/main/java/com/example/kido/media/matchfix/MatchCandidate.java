package com.example.kido.media.matchfix;

import java.util.LinkedHashSet;
import java.util.Set;

import lombok.Builder;
import lombok.Getter;

/**
 * One possible correct identity for a mis-matched item.
 *
 * <p>{@code matchPercent} is filled in by the service after scoring, not by whatever
 * produced the candidate, so every suggestion on the screen is ranked on the same
 * basis regardless of where it came from.
 */
@Getter
@Builder
public class MatchCandidate {

    /**
     * Stable identifier within a single response, so the client can post back the
     * candidate the owner selected.
     */
    private final String id;

    private final String title;
    private final Integer year;
    private final String plot;
    private final Integer runtimeMinutes;
    private final Double rating;
    private final String certification;
    private final String studio;
    private final String directors;
    private final String castMembers;
    private final String tmdbId;
    private final String imdbId;

    @Builder.Default
    private final Set<String> genres = new LinkedHashSet<>();

    /**
     * Where this came from, e.g. {@code sidecar}, {@code filename}, {@code folder},
     * {@code library} or {@code typed}. Shown so the owner can weigh a scraped record
     * differently from a re-reading of the filename.
     */
    private final String origin;

    /** Human sentence explaining why this is being offered. */
    private final String reason;

    /** Filled in by the scorer; 0–100. */
    private int matchPercent;

    void setMatchPercent(int matchPercent) {
        this.matchPercent = matchPercent;
    }
}
