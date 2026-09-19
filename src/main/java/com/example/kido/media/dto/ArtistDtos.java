package com.example.kido.media.dto;

import java.util.List;

import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;

/**
 * The artists grid and an artist's own page ("playlist" of everything by them) — a
 * tile shows a name and an image, not a raw list of {@code artist — song title} rows.
 * See {@link com.example.kido.media.music.ArtistNames} for how one credit line becomes
 * several artists, and {@link com.example.kido.media.music.ArtistService} for how a
 * tile's image is chosen.
 */
public final class ArtistDtos {

    private ArtistDtos() {}

    /**
     * @param representativeItemId a track id of theirs that has a poster, fetched the
     *                             normal way via {@code /api/media/items/{id}/poster} —
     *                             null only if none of their tracks has one yet
     */
    public record ArtistSummaryDto(String name, String representativeItemId, long trackCount) {}

    public record ArtistPageDto(
            List<ArtistSummaryDto> artists,
            int page,
            int size,
            long totalArtists,
            int totalPages) {}

    /** The artist's page: their full catalog, newest first. */
    public record ArtistDetailDto(
            String name,
            String representativeItemId,
            long trackCount,
            List<ItemSummaryDto> tracks) {}
}
