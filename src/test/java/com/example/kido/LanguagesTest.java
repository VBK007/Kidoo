package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.kido.media.metadata.Languages;

/**
 * The tag normalisation, tested without a database.
 *
 * <p>Worth isolating because it is the whole feature: every spelling that fails to
 * collapse onto one code splits a language in two, and the failure is silent — the
 * search simply returns a third of the library and nothing looks broken.
 */
class LanguagesTest {

    /** The case the facet exists for: three spellings of one language from three muxers. */
    @Test
    void everySpellingOfALanguageCollapsesOntoOneCode() {
        for (String spelling : List.of("ta", "tam", "Tamil", "TAMIL", " tam ")) {
            assertEquals(Optional.of("ta"), Languages.normalise(spelling), spelling);
        }
    }

    /**
     * Bibliographic codes are the trap: ffprobe passes through whatever the container
     * says, and half the world's encoders write "fre" where the JDK only knows "fra".
     */
    @Test
    void bibliographicAndTerminologicalCodesBothResolve() {
        assertEquals(Optional.of("fr"), Languages.normalise("fre"));
        assertEquals(Optional.of("fr"), Languages.normalise("fra"));
        assertEquals(Optional.of("de"), Languages.normalise("ger"));
        assertEquals(Optional.of("de"), Languages.normalise("deu"));
        assertEquals(Optional.of("zh"), Languages.normalise("chi"));
        assertEquals(Optional.of("nl"), Languages.normalise("dut"));
    }

    /** A region says where, not what. Splitting on it would file pt-BR away from pt. */
    @Test
    void regionQualifiersAreDropped() {
        assertEquals(Optional.of("pt"), Languages.normalise("pt-BR"));
        assertEquals(Optional.of("en"), Languages.normalise("en_US"));
    }

    /**
     * "und" is what ffprobe writes when the container says nothing. Kept out, because
     * a shared "und" facet across every untagged file reads like a language.
     */
    @Test
    void placeholdersAreNotLanguages() {
        for (String placeholder : List.of("und", "unknown", "none", "zxx", "mul", "", "   ")) {
            assertTrue(Languages.normalise(placeholder).isEmpty(), placeholder);
        }
        assertTrue(Languages.normalise(null).isEmpty());
    }

    /**
     * A tag outside the ISO tables is still a consistent label, so the files carrying
     * it still group together. Dropping them would lose more than an odd name costs.
     */
    @Test
    void unknownTagsAreKeptRatherThanDiscarded() {
        assertEquals(Optional.of("klingon"), Languages.normalise("Klingon"));
        assertTrue(Languages.normalise("x".repeat(64)).isEmpty(), "absurd tags are junk");
    }

    // --- audio track parsing ---

    /** The stored format is {@code index:codec:language:title}, entries split by ";". */
    @Test
    void languagesComeOutOfTheProbedTrackList() {
        Set<String> languages = Languages.fromAudioTracks(
                "0:eac3:tam:Tamil 5.1;1:aac:eng:English;2:aac:und:Commentary");

        assertEquals(List.of("ta", "en"), List.copyOf(languages));
    }

    /** Track order is the ranking: the first is what a player picks by default. */
    @Test
    void trackOrderIsPreserved() {
        Set<String> languages =
                Languages.fromAudioTracks("0:aac:hin:Hindi;1:eac3:tam:Tamil");
        assertEquals("hi", languages.iterator().next());
    }

    /** One title, one entry per language, however many tracks carry it. */
    @Test
    void repeatedLanguagesAreCountedOnce() {
        Set<String> languages = Languages.fromAudioTracks(
                "0:aac:eng:Stereo;1:eac3:eng:5.1;2:truehd:eng:Atmos");
        assertEquals(Set.of("en"), languages);
    }

    @Test
    void anUnprobedOrMalformedTrackListYieldsNothing() {
        assertTrue(Languages.fromAudioTracks(null).isEmpty());
        assertTrue(Languages.fromAudioTracks("").isEmpty());
        // Too few fields to carry a language — skipped, not crashed.
        assertTrue(Languages.fromAudioTracks("0:aac").isEmpty());
    }

    @Test
    void displayNamesAreResolvedForTheClient() {
        assertEquals("Tamil", Languages.displayName("ta"));
        assertEquals("Hindi", Languages.displayName("hi"));
        // An unresolvable code shows as itself rather than blank.
        assertEquals("klingon", Languages.displayName("klingon"));
        assertEquals("Unknown", Languages.displayName(null));
    }
}
