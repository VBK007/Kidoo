package com.example.kido.media.dto;

import java.util.List;

import com.example.kido.media.dto.ArtistDtos.ArtistSummaryDto;
import com.example.kido.media.dto.HomeDtos.HomeRailDto;

/**
 * The music tab's own home screen: mostly browse-by-facet rails (mood, activity, era,
 * music director) rather than {@link HomeDtos.HomeDto}'s lifetime popularity blend — a
 * home library's few hundred songs have no meaningful all-time play-count ranking the
 * way a film library's ratings do, so what a Spotify-style music home mostly offers is
 * ways *in* to the collection, not a judgement about which track is the library's best.
 *
 * <p>{@code rails} does carry one ranked exception: "Top music this week", built the
 * same way {@code HomeService}'s own weekly rail is — see {@code TrendingWindow} and
 * {@link com.example.kido.media.home.WeeklyPopularityRanker}. Recent activity is a
 * different, answerable question from all-time worth, and unlike a lifetime blend it
 * does not need a library with years of history behind it to mean something.
 *
 * <p>Reuses {@link HomeRailDto}/{@link HomeDtos.HomeItemDto} rather than inventing a
 * parallel shape — a rail is a rail, whichever screen it is drawn on. {@code
 * topArtists} does not fit that shape (a name and a track count, not a media item, per
 * {@link ArtistSummaryDto}) and gets its own field rather than a fake tile pointing at
 * one arbitrary track.
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
     * @param topArtists        the library's busiest singers by track count, most first
     *                          — the same ranking {@code /api/media/artists} itself
     *                          uses, repeated here so the music home screen does not
     *                          need a second request just to lead with a few of them
     * @param generatedAt       ISO-8601 instant the response was composed
     */
    public record MusicHomeDto(
            List<CatalogDtos.ItemSummaryDto> continueListening,
            List<HomeRailDto> rails,
            List<ArtistSummaryDto> topArtists,
            String generatedAt) {}
}
