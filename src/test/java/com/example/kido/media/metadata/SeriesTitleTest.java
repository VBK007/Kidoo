package com.example.kido.media.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Getting a show's name out of one episode's filename.
 *
 * <p>The TV lookup searches TMDB with whatever the item is titled, and on a real
 * library that is "[Anime Time] Black Lagoon - 029 - Collateral Massacre" — which
 * matches nothing, so a whole shelf of anime stayed without poster, story or cast.
 */
class SeriesTitleTest {

    @Test
    void stripsTheFansubGroupAndTheEpisode() {
        assertThat(FilenameParser.seriesTitle(
                "[Anime Time] Black Lagoon - 029 - Collateral Massacre"))
                .isEqualTo("Black Lagoon");
    }

    @Test
    void stripsATrailingResolution() {
        assertThat(FilenameParser.seriesTitle(
                "X-Men (Marvel ANIME) - Episode 01 - The Return Joining Forces (720p)"))
                .isEqualTo("X-Men (Marvel ANIME)");
    }

    @Test
    void keepsBracketsThatAreNotAReleaseGroup() {
        // Only a bracket at the very front is a group tag; one inside the name is the
        // name — "X-Men (Marvel ANIME)" is how TMDB itself distinguishes that series.
        assertThat(FilenameParser.seriesTitle("X-Men (Marvel ANIME) - Episode 05 (1080p60)"))
                .isEqualTo("X-Men (Marvel ANIME)");
    }

    @Test
    void leavesAnOrdinaryTitleAlone() {
        assertThat(FilenameParser.seriesTitle("Sita Ramam")).isEqualTo("Sita Ramam");
        assertThat(FilenameParser.seriesTitle("KGF: Chapter 2")).isEqualTo("KGF: Chapter 2");
    }

    /**
     * A hyphen inside a name is not an episode separator, so only " - " with spaces
     * around it cuts. Otherwise "Spider-Man" would look up as "Spider".
     */
    @Test
    void doesNotCutOnAHyphenatedWord() {
        assertThat(FilenameParser.seriesTitle("Spider-Man")).isEqualTo("Spider-Man");
        assertThat(FilenameParser.seriesTitle("X-Men")).isEqualTo("X-Men");
    }

    @Test
    void neverReturnsAnEmptyQuery() {
        // Nothing survives the cuts, so the original is kept: a messy search beats a
        // blank one, which would match everything or nothing at random.
        assertThat(FilenameParser.seriesTitle("[Group] - 029 -")).isEqualTo("[Group] - 029 -");
        assertThat(FilenameParser.seriesTitle("")).isEqualTo("");
        assertThat(FilenameParser.seriesTitle(null)).isNull();
    }
}
