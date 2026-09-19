package com.example.kido.media.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;

/**
 * Thinning a "recently added" shelf to one tile per release.
 *
 * <p>Media arrives in clumps — a soundtrack copied across, a season of anime — so
 * the newest twenty files were routinely twenty pieces of one thing.
 */
class OneItemPerReleaseTest {

    @Test
    void keepsOneTrackPerAlbum() {
        List<ItemSummaryDto> thinned = CatalogService.oneItemPerRelease(
                List.of(
                        music("1", "Vikram Title Track", "Vikram"),
                        music("2", "Pathala Pathala", "Vikram"),
                        music("3", "Once Upon A Time", "Vikram"),
                        music("4", "Arabic Kuthu", "Beast")),
                10);

        assertThat(thinned).extracting(ItemSummaryDto::id).containsExactly("1", "4");
    }

    @Test
    void keepsTheNewestOfAReleaseRatherThanAnyOther() {
        // The list arrives newest first, so the survivor must be the one at the
        // front — it is what "recently added" claims to be showing.
        List<ItemSummaryDto> thinned = CatalogService.oneItemPerRelease(
                List.of(
                        music("newest", "Pathala Pathala", "Vikram"),
                        music("older", "Vikram Title Track", "Vikram")),
                10);

        assertThat(thinned).extracting(ItemSummaryDto::id).containsExactly("newest");
    }

    @Test
    void ignoresCaseAndStraySpacingInTheAlbumTag() {
        // Two taggers, one record. Without this the shelf shows it twice.
        List<ItemSummaryDto> thinned = CatalogService.oneItemPerRelease(
                List.of(
                        music("1", "One", "Vikram"),
                        music("2", "Two", "  vikram "),
                        music("3", "Three", "VIKRAM")),
                10);

        assertThat(thinned).extracting(ItemSummaryDto::id).containsExactly("1");
    }

    @Test
    void collapsesEpisodesOfOneSeries() {
        // No album tag anywhere here; the title carries the series instead. This is
        // what a season landing in one second looks like on the home screen.
        List<ItemSummaryDto> thinned = CatalogService.oneItemPerRelease(
                List.of(
                        video("1", "[Anime Time] Black Lagoon - 029 - Collateral Massacre"),
                        video("2", "[Anime Time] Black Lagoon - 028 - Owl Hunt"),
                        video("3", "[Anime Time] Black Lagoon - 027 - Angels"),
                        video("4", "X-Men (Marvel ANIME) - Episode 5")),
                10);

        assertThat(thinned).extracting(ItemSummaryDto::id).containsExactly("1", "4");
    }

    @Test
    void collapsesSongsSharingAFilmNameInTheirTitle() {
        List<ItemSummaryDto> thinned = CatalogService.oneItemPerRelease(
                List.of(
                        video("1", "Udhayam NH4 - Yaaro Ivan Video"),
                        video("2", "Udhayam NH4 - Naan Nee Video"),
                        video("3", "Thani Oruvan - Kannala Kannala Video")),
                10);

        assertThat(thinned).extracting(ItemSummaryDto::id).containsExactly("1", "3");
    }

    /**
     * Where the rule gets its safety.
     *
     * <p>Ordinary film titles carry no " - ", so nothing groups them and a folder of
     * unrelated films comes through whole. Over-collapsing here would hide most of
     * somebody's library from the one shelf that tells them what is new.
     */
    @Test
    void leavesOrdinaryFilmsAlone() {
        List<ItemSummaryDto> thinned = CatalogService.oneItemPerRelease(
                List.of(
                        video("1", "Sita Ramam"),
                        video("2", "KGF: Chapter 2"),
                        video("3", "Top Gun Maverick"),
                        video("4", "Vikram")),
                10);

        assertThat(thinned).hasSize(4);
    }

    @Test
    void doesNotGroupOnAVeryShortPrefix() {
        // "A - " is not a release anybody named; grouping on it would put unrelated
        // things together for nothing.
        List<ItemSummaryDto> thinned = CatalogService.oneItemPerRelease(
                List.of(video("1", "A - One"), video("2", "A - Two")),
                10);

        assertThat(thinned).hasSize(2);
    }

    @Test
    void keepsEveryTrackThatHasNeitherAlbumNorPrefix() {
        // Untagged loose files are not one record arriving many times; folding them
        // together under a shared "no album" would hide all but one.
        List<ItemSummaryDto> thinned = CatalogService.oneItemPerRelease(
                List.of(
                        music("1", "Loose One", null),
                        music("2", "Loose Two", ""),
                        music("3", "Loose Three", "   ")),
                10);

        assertThat(thinned).extracting(ItemSummaryDto::id).containsExactly("1", "2", "3");
    }

    @Test
    void stopsAtTheLimit() {
        List<ItemSummaryDto> thinned = CatalogService.oneItemPerRelease(
                List.of(
                        music("1", "A", "Album A"),
                        music("2", "B", "Album B"),
                        music("3", "C", "Album C")),
                2);

        assertThat(thinned).extracting(ItemSummaryDto::id).containsExactly("1", "2");
    }

    @Test
    void handlesAnEmptyLibrary() {
        assertThat(CatalogService.oneItemPerRelease(List.of(), 10)).isEmpty();
    }

    private static ItemSummaryDto music(String id, String title, String album) {
        return summary(id, "MUSIC", title, album);
    }

    private static ItemSummaryDto video(String id, String title) {
        return summary(id, "ANIME", title, null);
    }

    private static ItemSummaryDto summary(String id, String type, String title, String album) {
        return new ItemSummaryDto(
                id, type, title, null, null, null, null, Set.of(),
                false, false, false, null, false, null, null,
                null, album, null, null, null, null, 0L, 0L, false);
    }
}
