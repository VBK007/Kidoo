package com.example.kido.media.music;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Splitting a credit line into people.
 *
 * <p>The singer and hero shelves stand on this: a name parsed wrongly is a shelf
 * headed by half a name, or two shelves for one person.
 */
class CreditNamesTest {

    @Test
    void splitsADuetIntoBothSingers() {
        assertThat(MusicHomeService.namesIn("A.R. Rahman,Shreya Ghoshal,Sarthak Kalyani"))
                .containsExactly("A.R. Rahman", "Shreya Ghoshal", "Sarthak Kalyani");
    }

    @Test
    void trimsTheSpaceTaggersLeaveAfterAComma() {
        // Both spellings are real and in the same library.
        assertThat(MusicHomeService.namesIn("A.R.Rahman, Sunitha Sarathy, Pop Shalini"))
                .containsExactly("A.R.Rahman", "Sunitha Sarathy", "Pop Shalini");
    }

    @Test
    void dropsEmptyFragments() {
        assertThat(MusicHomeService.namesIn("Ilaiyaraaja,,S. Janaki,"))
                .containsExactly("Ilaiyaraaja", "S. Janaki");
    }

    @Test
    void keepsASingleNameWhole() {
        // "&" is part of how a duet gets written and is not a separator this splits
        // on — one credit, shown as it was tagged.
        assertThat(MusicHomeService.namesIn("K.J.Yesudas & S.Janaki"))
                .containsExactly("K.J.Yesudas & S.Janaki");
    }

    @Test
    void treatsMissingCreditsAsNobody() {
        assertThat(MusicHomeService.namesIn(null)).isEmpty();
        assertThat(MusicHomeService.namesIn("")).isEmpty();
        assertThat(MusicHomeService.namesIn("   ")).isEmpty();
    }
}
