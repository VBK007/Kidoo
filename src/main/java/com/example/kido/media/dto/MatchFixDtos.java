package com.example.kido.media.dto;

import java.util.List;
import java.util.Set;

import com.example.kido.media.matchfix.MatchCandidate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Read and write models for the fix-wrong-metadata screen.
 *
 * <p>This is the one place the API deliberately exposes real filesystem paths. Every
 * other endpoint addresses files by id precisely so paths never leak, but here the path
 * <em>is</em> the useful information: the owner is being asked to identify a file, and
 * the folder it sits in is usually the strongest clue. It is owner-gated for that
 * reason.
 */
public final class MatchFixDtos {

    private MatchFixDtos() {}

    /** One row in the mismatch queue. */
    public record MismatchDto(
            String mediaItemId,
            String type,
            String currentTitle,
            Integer currentYear,
            String fileName,
            String folderPath,
            String metadataSource,
            long fileSize,
            boolean hasPoster) {}

    public record MismatchQueueDto(
            List<MismatchDto> items,
            int page,
            int size,
            long totalRemaining,
            int totalPages) {}

    public record CandidateDto(
            String id,
            String title,
            Integer year,
            String plot,
            Integer runtimeMinutes,
            Double rating,
            String certification,
            String studio,
            String directors,
            Set<String> genres,
            String tmdbId,
            String imdbId,
            String origin,
            String reason,
            int matchPercent) {

        public static CandidateDto from(MatchCandidate candidate) {
            return new CandidateDto(
                    candidate.getId(),
                    candidate.getTitle(),
                    candidate.getYear(),
                    candidate.getPlot(),
                    candidate.getRuntimeMinutes(),
                    candidate.getRating(),
                    candidate.getCertification(),
                    candidate.getStudio(),
                    candidate.getDirectors(),
                    candidate.getGenres(),
                    candidate.getTmdbId(),
                    candidate.getImdbId(),
                    candidate.getOrigin(),
                    candidate.getReason(),
                    candidate.getMatchPercent());
        }
    }

    /**
     * Everything the fix screen renders for one item.
     *
     * @param reassurance the promise the screen makes to the user, sent from the server
     *                    so the client and the actual behaviour cannot drift apart
     * @param remaining   how many more are queued behind this one
     */
    public record FixScreenDto(
            MismatchDto item,
            List<CandidateDto> candidates,
            String candidateSource,
            String reassurance,
            long remaining) {}

    /**
     * Applies a correction.
     *
     * <p>Either post back a {@code candidateId} from the last response, or supply a
     * title directly — the screen has a free-text field precisely so someone who knows
     * the answer is never stuck behind a list that lacks it.
     */
    public record ApplyMatchRequest(
            String candidateId,
            @Size(max = 512) String title,
            Integer year) {}

    /** Moves an item between categories, e.g. a misfiled clip out of Films into Ours. */
    public record ReclassifyRequest(@NotBlank String type) {}

    /** What a correction did, plus the queue count for the client's mono line. */
    public record FixResultDto(
            String mediaItemId,
            String title,
            Integer year,
            String type,
            String metadataSource,
            boolean typeLocked,
            boolean hidden,
            String detail,
            long remaining) {}
}
