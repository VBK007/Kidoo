package com.example.kido.media.music;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;

/**
 * Thinning "Recently added" to one track per album.
 *
 * <p>Tracks arrive in album-sized clumps — copying one soundtrack across writes
 * twenty rows inside the same second — so the twenty newest files were routinely
 * twenty songs off the same record.
 */
class OneTrackPerAlbumTest {

    @Test
    void keepsOnlyTheFirstTrackFromEachAlbum() {
        List<ItemSummaryDto> thinned = MusicHomeService.oneTrackPerAlbum(
                List.of(
                        track("1", "Vikram Title Track", "Vikram"),
                        track("2", "Pathala Pathala", "Vikram"),
                        track("3", "Once Upon A Time", "Vikram"),
                        track("4", "Arabic Kuthu", "Beast")),
                10);

        assertThat(thinned).extracting(ItemSummaryDto::id).containsExactly("1", "4");
    }

    @Test
    void keepsTheNewestTrackOfAnAlbumRatherThanAnyOther() {
        // The list arrives newest first, so the survivor must be the one at the
        // front — it is what "recently added" is claiming to show.
        List<ItemSummaryDto> thinned = MusicHomeService.oneTrackPerAlbum(
                List.of(
                        track("newest", "Pathala Pathala", "Vikram"),
                        track("older", "Vikram Title Track", "Vikram")),
                10);

        assertThat(thinned).extracting(ItemSummaryDto::id).containsExactly("newest");
    }

    @Test
    void ignoresCaseAndStraySpacingInTheAlbumTag() {
        // Two taggers, one record. Without this the rail shows it twice.
        List<ItemSummaryDto> thinned = MusicHomeService.oneTrackPerAlbum(
                List.of(
                        track("1", "One", "Vikram"),
                        track("2", "Two", "  vikram "),
                        track("3", "Three", "VIKRAM")),
                10);

        assertThat(thinned).extracting(ItemSummaryDto::id).containsExactly("1");
    }

    /**
     * The half that would quietly lose somebody's music.
     *
     * <p>Untagged files are not evidence of one record arriving many times; they
     * are many loose files. Collapsing them under a shared "no album" would hide
     * all but one, which is the opposite of the point.
     */
    @Test
    void keepsEveryTrackThatHasNoAlbum() {
        List<ItemSummaryDto> thinned = MusicHomeService.oneTrackPerAlbum(
                List.of(
                        track("1", "Loose One", null),
                        track("2", "Loose Two", ""),
                        track("3", "Loose Three", "   ")),
                10);

        assertThat(thinned).extracting(ItemSummaryDto::id).containsExactly("1", "2", "3");
    }

    @Test
    void stopsAtTheLimit() {
        List<ItemSummaryDto> thinned = MusicHomeService.oneTrackPerAlbum(
                List.of(
                        track("1", "A", "Album A"),
                        track("2", "B", "Album B"),
                        track("3", "C", "Album C")),
                2);

        assertThat(thinned).extracting(ItemSummaryDto::id).containsExactly("1", "2");
    }

    @Test
    void handlesAnEmptyLibrary() {
        assertThat(MusicHomeService.oneTrackPerAlbum(List.of(), 10)).isEmpty();
    }

    private static ItemSummaryDto track(String id, String title, String album) {
        return new ItemSummaryDto(
                id, "MUSIC", title, null, null, null, null, Set.of(),
                false, false, false, null, false, null, null,
                "Anirudh Ravichander", album, null, null, null, 0L, 0L, false);
    }
}
