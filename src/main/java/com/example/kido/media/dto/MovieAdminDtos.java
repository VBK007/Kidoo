package com.example.kido.media.dto;

import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Request and response shapes for creating and editing a title by hand. */
public final class MovieAdminDtos {

    private MovieAdminDtos() {}

    /**
     * Everything the movie form can set, for an insert and for an edit alike.
     *
     * <p>One shape for both because the difference is a single field: an {@code id}
     * names an existing title to change, and its absence asks for a new one. Two
     * endpoints would mean two validators and two sets of defaults that drift apart.
     *
     * <p><strong>Omitted is not the same as empty.</strong> A null field is left exactly
     * as it was — so a form that only edits the plot sends only the plot — while an
     * empty string or empty array clears the field. Without that distinction a partial
     * form would silently wipe everything it did not happen to show.
     *
     * @param id          absent to insert, present to update
     * @param title       required when inserting; when updating, null leaves it alone
     * @param type        {@code FILM} (default for a new title), {@code ANIME},
     *                    {@code HOME_VIDEO}, {@code MUSIC}, {@code PHOTO}
     * @param filePath    the video on disk, which must sit inside a configured library.
     *                    Optional: a title with no file is catalogued as missing, which
     *                    is how an entry is made for something not yet on the disk
     * @param actors      the cast, in billing order; stored joined as {@code castMembers}
     * @param sortTitle   what the A-Z sort uses; derived from the title when not given
     */
    public record MovieUpsertRequest(
            String id,
            @Size(max = 512) String title,
            @Size(max = 512) String originalTitle,
            @Size(max = 512) String sortTitle,
            String type,
            @Min(1870) @Max(2200) Integer year,
            @Size(max = 20_000) String plot,
            @Size(max = 1000) String tagline,
            @Min(0) @Max(100_000) Integer runtimeMinutes,
            @DecimalMin("0.0") @DecimalMax("10.0") Double rating,
            @Size(max = 32) String certification,
            List<String> genres,
            List<String> directors,
            List<String> actors,
            @Size(max = 256) String studio,
            String releaseDate,
            @Size(max = 64) String tmdbId,
            @Size(max = 64) String imdbId,
            @Size(max = 64) String quality,
            @Size(max = 1024) String filePath,
            @Size(max = 256) String libraryName) {}

    /**
     * The title as it now stands, in the shape the form posted it.
     *
     * <p>Lists come back as lists even though the column is a joined string, so the
     * client can round-trip an edit without re-splitting anything.
     *
     * @param created     true when this call inserted the row rather than updating it
     * @param missing     true when no playable file backs it — either the disk is
     *                    offline or the title was catalogued without one
     * @param posterUrl   where to fetch the thumbnail, or null if it has none
     * @param commentCount included so the admin list can show engagement beside the
     *                    fields it edits
     */
    public record MovieAdminDto(
            String id,
            boolean created,
            String type,
            String libraryName,
            String title,
            String originalTitle,
            String sortTitle,
            Integer year,
            String plot,
            String tagline,
            Integer runtimeMinutes,
            Double rating,
            String certification,
            List<String> genres,
            List<String> directors,
            List<String> actors,
            String studio,
            String releaseDate,
            String tmdbId,
            String imdbId,
            String quality,
            String filePath,
            boolean missing,
            boolean hasPoster,
            boolean hasBackdrop,
            String posterUrl,
            String backdropUrl,
            String metadataSource,
            long viewCount,
            long likeCount,
            long commentCount,
            String addedAt,
            String updatedAt) {}
}
