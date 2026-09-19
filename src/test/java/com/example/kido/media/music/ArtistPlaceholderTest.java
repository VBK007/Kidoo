package com.example.kido.media.music;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Credits that name nobody.
 *
 * <p>Found on the real library: two hundred and twenty tracks whose artist was
 * {@code usb}, because a file with no tags falls back to the folder above it and
 * that folder was where the disk had been plugged in. They earned a singer shelf
 * of their own on the music screen.
 */
class ArtistPlaceholderTest {

    @Test
    void recognisesTheStandInsTaggersAndRippersLeave() {
        assertThat(ArtistNames.isPlaceholder("usb")).isTrue();
        assertThat(ArtistNames.isPlaceholder("Unknown Artist")).isTrue();
        assertThat(ArtistNames.isPlaceholder("various artists")).isTrue();
        assertThat(ArtistNames.isPlaceholder("N/A")).isTrue();
        assertThat(ArtistNames.isPlaceholder("  USB  ")).isTrue();
        assertThat(ArtistNames.isPlaceholder(null)).isTrue();
        assertThat(ArtistNames.isPlaceholder("   ")).isTrue();
    }

    /**
     * The half that matters more.
     *
     * <p>A real singer wrongly called a placeholder loses their whole catalogue from
     * the artists grid, which is a far louder failure than one junk shelf. EDISON is
     * in here on purpose: it looked like junk next to "usb" on the same library, and
     * it turned out to be the credited artist of a game soundtrack.
     */
    @Test
    void leavesRealNamesAlone() {
        assertThat(ArtistNames.isPlaceholder("EDISON")).isFalse();
        assertThat(ArtistNames.isPlaceholder("Ilaiyaraaja")).isFalse();
        assertThat(ArtistNames.isPlaceholder("A.R. Rahman")).isFalse();
        assertThat(ArtistNames.isPlaceholder("Nakash Aziz")).isFalse();
        // Short and lowercase, and still somebody.
        assertThat(ArtistNames.isPlaceholder("sid")).isFalse();
    }

    @Test
    void aPlaceholderCreditSplitsIntoNobody() {
        assertThat(ArtistNames.split("usb")).isEmpty();
        assertThat(ArtistNames.split("Unknown Artist")).isEmpty();
    }

    @Test
    void dropsOnlyThePlaceholderHalfOfAMixedCredit() {
        // One real person and one absence.
        assertThat(ArtistNames.split("A.R. Rahman, Unknown"))
                .containsExactly("A.R. Rahman");
    }

    @Test
    void stillSplitsOrdinaryCollaborations() {
        assertThat(ArtistNames.split("Anirudh Ravichander, Badshah"))
                .containsExactly("Anirudh Ravichander", "Badshah");
    }
}
