package com.example.kido.media.dto;

import java.util.List;

import com.example.kido.media.dto.HomeDtos.HomeRailDto;

/**
 * The music tab's own home screen: browse-by-facet rails (mood, activity, era, music
 * director) rather than {@link HomeDtos.HomeDto}'s popularity blend — a home library's
 * few hundred songs have no meaningful play-count ranking the way a film library's
 * ratings do, so what a Spotify-style music home actually offers is ways *in* to the
 * collection, not a judgement about which track is best.
 *
 * <p>Reuses {@link HomeRailDto}/{@link HomeDtos.HomeItemDto} rather than inventing a
 * parallel shape — a rail is a rail, whichever screen it is drawn on.
 */
public final class MusicHomeDtos {

    private MusicHomeDtos() {}

    /**
     * @param continueListening tracks this profile started and did not finish, newest
     *                          first — filtered to audio types, unlike the profile-wide
     *                          list {@link com.example.kido.media.playback.PlaybackService
     *                          #continueWatching} returns
     * @param rails             ordered as the client should render them; empty rails
     *                          (a mood or era with too few tracks to be worth a shelf)
     *                          are omitted rather than sent as a bare heading
     * @param generatedAt       ISO-8601 instant the response was composed
     */
    public record MusicHomeDto(
            List<CatalogDtos.ItemSummaryDto> continueListening,
            List<HomeRailDto> rails,
            String generatedAt) {}
}
