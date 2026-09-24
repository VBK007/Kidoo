package com.example.kido.media.dto;

import java.util.List;

import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;
import com.example.kido.media.dto.CatalogDtos.LibrarySummaryDto;
import com.example.kido.media.dto.PlaybackDtos.ContinueWatchingDto;

import tools.jackson.databind.JsonNode;

/**
 * The whole home screen in one response.
 *
 * <p>Composed server-side rather than left to the client to assemble from five calls:
 * the rails are ranked against each other, so they have to be built from one consistent
 * read of the library, and a phone on house wifi pays for every extra round trip before
 * it can draw anything.
 */
public final class HomeDtos {

    private HomeDtos() {}

    /**
     * One poster on a rail, with the numbers behind its placement.
     *
     * @param score  0–1 blend, present only on the ranked rail; null where the rail
     *               sorts on a single signal and the score would be meaningless
     * @param reason short subtitle naming why it is here, e.g. {@code Played 12 times}
     */
    public record HomeItemDto(ItemSummaryDto item, Double score, String reason) {}

    /**
     * A horizontal row on the home screen.
     *
     * @param key      stable identifier the client keys its row state on
     * @param title    the row heading, as shown
     * @param rankedBy which signal ordered it: {@code popularity}, {@code rating},
     *                 {@code views}, {@code watchTime}, {@code likes} or
     *                 {@code added}
     */
    public record HomeRailDto(String key, String title, String rankedBy,
                              List<HomeItemDto> items) {}

    /**
     * @param rails     ordered as the client should render them; empty rails are omitted
     *                  rather than sent as bare headings
     * @param weights   how the ranked rail was weighted, so the app (and anyone asking
     *                  why a title placed where it did) can see the formula
     * @param generatedAt ISO-8601 instant the response was composed
     * @param mirror    titles from the mirror's home page with their playlists; null unless
     *                  a parent's request carried a mirror session and the mirror answered.
     *                  Its own field rather than one of {@code rails}: its tiles are not
     *                  library items, and a client parsing rails would choke on them
     */
    public record HomeDto(
            List<ContinueWatchingDto> continueWatching,
            List<HomeRailDto> rails,
            LibrarySummaryDto library,
            RankingWeightsDto weights,
            String generatedAt,
            MirrorRailDto mirror) {

        public HomeDto withMirror(MirrorRailDto rail) {
            return new HomeDto(continueWatching, rails, library, weights, generatedAt, rail);
        }
    }

    /**
     * One title from the mirror's home page.
     *
     * @param playlist what the mirror's playlist.php answered for it, as JSON; null when that
     *                 call failed or ran past the home screen's time budget, so the client
     *                 fetches it on tap from {@code /playback/playlist} instead
     */
    public record MirrorTileDto(String id, String title, String image, JsonNode playlist) {}

    /** The mirror's titles, in the order its home page lists them. */
    public record MirrorRailDto(String key, String title, List<MirrorTileDto> items) {}

    /** The weights behind the blended rail. Sum to 1. */
    public record RankingWeightsDto(double rating, double views, double likes) {}
}
