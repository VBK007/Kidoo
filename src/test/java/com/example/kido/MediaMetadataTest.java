package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.kido.media.metadata.FilenameParser;
import com.example.kido.media.metadata.NfoParser;
import com.example.kido.media.metadata.SidecarLocator;
import com.example.kido.media.metadata.SidecarMetadata;
import com.example.kido.media.metadata.SubtitleConverter;

/**
 * Unit coverage for the metadata layer — the parsers that turn a messy disk into a
 * catalog. Deliberately not a Spring test: these are pure functions over files, and
 * the interesting cases are the malformed inputs a real movie folder is full of.
 */
class MediaMetadataTest {

    private final FilenameParser filenames = new FilenameParser();
    private final NfoParser nfoParser = new NfoParser();
    private final SidecarLocator locator = new SidecarLocator();
    private final SubtitleConverter subtitles = new SubtitleConverter();

    // --- filename parsing ---

    @Test
    void parsesDottedReleaseName() {
        FilenameParser.Parsed parsed = filenames.parse("Inception.2010.1080p.BluRay.x264-GROUP");
        assertEquals("Inception", parsed.title());
        assertEquals(2010, parsed.year());
        assertEquals("1080p BluRay", parsed.quality());
    }

    @Test
    void parsesParenthesisedYear() {
        FilenameParser.Parsed parsed = filenames.parse("The Matrix (1999) [2160p]");
        assertEquals("The Matrix", parsed.title());
        assertEquals(1999, parsed.year());
        assertEquals("2160p", parsed.quality());
    }

    @Test
    void takesTrailingYearWhenTitleStartsWithNumber() {
        // "2001" here is part of the title, not the release year.
        FilenameParser.Parsed parsed = filenames.parse("2001.A.Space.Odyssey.1968.1080p.BluRay");
        assertEquals(1968, parsed.year());
        assertTrue(parsed.title().startsWith("2001"), "got: " + parsed.title());
    }

    @Test
    void survivesFilenameWithNoYearOrTags() {
        FilenameParser.Parsed parsed = filenames.parse("Home Video Clip");
        assertEquals("Home Video Clip", parsed.title());
        assertNull(parsed.year());
        assertNull(parsed.quality());
    }

    @Test
    void stripsLeadingArticleForSorting() {
        assertEquals("matrix", FilenameParser.sortTitle("The Matrix"));
        assertEquals("inception", FilenameParser.sortTitle("Inception"));
    }

    // --- capture dates, which drive the home-video timeline ---

    @Test
    void readsCaptureStampFromPhoneFilenames() {
        // The three dominant conventions: Android, WhatsApp, Pixel.
        assertEquals("2024-01-02", localDate(filenames.captureInstant("VID_20240102_181500")));
        assertEquals("2023-12-25", localDate(filenames.captureInstant("IMG-20231225-WA0003")));
        assertEquals("2024-01-02", localDate(filenames.captureInstant("PXL_20240102_181500123")));
    }

    @Test
    void readsCaptureStampFromDashedAndDottedForms() {
        assertEquals("2024-03-14", localDate(filenames.captureInstant("2024-03-14 18.15.00")));
        assertEquals("2024-03-14", localDate(filenames.captureInstant("2024-03-14")));
    }

    @Test
    void ignoresFilenamesWithNoDate() {
        assertTrue(filenames.captureInstant("Holiday clip").isEmpty());
        assertTrue(filenames.captureInstant("Inception.2010.1080p").isEmpty(),
                "a bare release year is not a capture stamp");
    }

    /** A date-shaped but impossible string must not become a wrong timestamp. */
    @Test
    void rejectsImpossibleDates() {
        assertTrue(filenames.captureInstant("VID_20240230_120000").isEmpty());
        assertTrue(filenames.captureInstant("VID_20241340_120000").isEmpty());
    }

    /** An out-of-range time falls back to midnight rather than discarding the date. */
    @Test
    void keepsDateWhenTimeIsNonsense() {
        Optional<Instant> parsed = filenames.captureInstant("VID_20240102_995500");
        assertEquals("2024-01-02", localDate(parsed));
    }

    private static String localDate(Optional<Instant> instant) {
        return instant.map(value -> value.atZone(ZoneId.systemDefault()).toLocalDate().toString())
                .orElse("absent");
    }

    // --- .nfo parsing ---

    @Test
    void parsesKodiNfo(@TempDir Path dir) throws Exception {
        Path nfo = dir.resolve("movie.nfo");
        Files.writeString(nfo, """
                <?xml version="1.0" encoding="UTF-8"?>
                <movie>
                  <title>Inception</title>
                  <originaltitle>Inception</originaltitle>
                  <year>2010</year>
                  <plot>A thief who steals corporate secrets.</plot>
                  <tagline>Your mind is the scene of the crime.</tagline>
                  <runtime>148</runtime>
                  <mpaa>PG-13</mpaa>
                  <genre>Action</genre>
                  <genre>Science Fiction / Thriller</genre>
                  <director>Christopher Nolan</director>
                  <studio>Warner Bros.</studio>
                  <premiered>2010-07-16</premiered>
                  <uniqueid type="tmdb">27205</uniqueid>
                  <uniqueid type="imdb">tt1375666</uniqueid>
                  <ratings>
                    <rating name="themoviedb" max="10" default="true">
                      <value>8.4</value>
                    </rating>
                  </ratings>
                  <actor><name>Leonardo DiCaprio</name><role>Cobb</role></actor>
                  <actor><name>Elliot Page</name><role>Ariadne</role></actor>
                </movie>
                """, StandardCharsets.UTF_8);

        SidecarMetadata meta = nfoParser.parse(nfo).orElseThrow();
        assertEquals("Inception", meta.title());
        assertEquals(2010, meta.year());
        assertEquals(148, meta.runtimeMinutes());
        assertEquals(8.4, meta.rating());
        assertEquals("PG-13", meta.certification());
        assertEquals("27205", meta.tmdbId());
        assertEquals("tt1375666", meta.imdbId());
        assertEquals(2010, meta.releaseDate().getYear());
        assertEquals(List.of("Christopher Nolan"), meta.directors());
        assertEquals(2, meta.cast().size());
        // The compound <genre> element is split into separate genres.
        assertTrue(meta.genres().containsAll(List.of("Action", "Science Fiction", "Thriller")),
                "got: " + meta.genres());
    }

    @Test
    void rescalesRatingFromNonTenScale() throws Exception {
        Path nfo = Files.createTempFile("rating", ".nfo");
        Files.writeString(nfo, """
                <movie><title>X</title>
                  <ratings><rating max="100" default="true"><value>84</value></rating></ratings>
                </movie>
                """);
        assertEquals(8.4, nfoParser.parse(nfo).orElseThrow().rating());
    }

    @Test
    void readsNfoWithJunkPreamble(@TempDir Path dir) throws Exception {
        // Scene .nfo files routinely carry ASCII art before the XML.
        Path nfo = dir.resolve("movie.nfo");
        Files.writeString(nfo, """
                ====== RELEASE INFO ======
                <movie><title>Arrival</title><year>2016</year></movie>
                """);
        SidecarMetadata meta = nfoParser.parse(nfo).orElseThrow();
        assertEquals("Arrival", meta.title());
        assertEquals(2016, meta.year());
    }

    @Test
    void rejectsMalformedNfoWithoutThrowing(@TempDir Path dir) throws Exception {
        Path nfo = dir.resolve("movie.nfo");
        Files.writeString(nfo, "<movie><title>Broken");
        assertTrue(nfoParser.parse(nfo).isEmpty());
    }

    /**
     * The security-relevant case: an entity referencing a local file must not be
     * expanded. A vulnerable parser would inline the secret into the title.
     */
    @Test
    void refusesExternalEntities(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-VALUE");
        Path nfo = dir.resolve("movie.nfo");
        Files.writeString(nfo, """
                <?xml version="1.0"?>
                <!DOCTYPE movie [ <!ENTITY xxe SYSTEM "file:///%s"> ]>
                <movie><title>&xxe;</title></movie>
                """.formatted(secret.toString().replace("\\", "/")));

        Optional<SidecarMetadata> parsed = nfoParser.parse(nfo);
        // DOCTYPE is rejected outright, so the document does not parse at all.
        assertTrue(parsed.isEmpty(), "DOCTYPE should be refused");
        parsed.ifPresent(meta ->
                assertFalse(String.valueOf(meta.title()).contains("TOP-SECRET-VALUE")));
    }

    // --- sidecar discovery ---

    @Test
    void findsArtworkAndSubtitlesBesideMovie(@TempDir Path dir) throws Exception {
        Path folder = Files.createDirectories(dir.resolve("Inception (2010)"));
        Path movie = folder.resolve("Inception.2010.1080p.mkv");
        Files.writeString(movie, "not really a video");
        Files.writeString(folder.resolve("poster.jpg"), "img");
        Files.writeString(folder.resolve("fanart.jpg"), "img");
        Files.writeString(folder.resolve("Inception.2010.1080p.en.srt"), "1\n");
        Files.writeString(folder.resolve("Inception.2010.1080p.fr.forced.srt"), "1\n");

        assertTrue(locator.findPoster(movie).isPresent());
        assertTrue(locator.findBackdrop(movie).isPresent());

        List<SidecarLocator.SubtitleTrack> tracks = locator.findSubtitles(movie);
        assertEquals(2, tracks.size());
        assertTrue(tracks.stream().anyMatch(t -> t.language().equals("en")));
        assertTrue(tracks.stream().anyMatch(t -> t.language().equals("fr") && t.forced()));
    }

    @Test
    void prefersMovieSpecificPosterOverFolderArtwork(@TempDir Path dir) throws Exception {
        Path movie = dir.resolve("Dune.2021.mkv");
        Files.writeString(movie, "x");
        Files.writeString(dir.resolve("folder.jpg"), "generic");
        Files.writeString(dir.resolve("Dune.2021-poster.jpg"), "specific");

        Path found = locator.findPoster(movie).orElseThrow();
        assertEquals("Dune.2021-poster.jpg", found.getFileName().toString());
    }

    @Test
    void findsNfoPerFileBeforeGenericMovieNfo(@TempDir Path dir) throws Exception {
        Path movie = dir.resolve("Dune.2021.mkv");
        Files.writeString(movie, "x");
        Files.writeString(dir.resolve("movie.nfo"), "<movie><title>Generic</title></movie>");
        Files.writeString(dir.resolve("Dune.2021.nfo"), "<movie><title>Specific</title></movie>");

        Path found = locator.findNfo(movie).orElseThrow();
        assertEquals("Dune.2021.nfo", found.getFileName().toString());
    }

    // --- subtitle conversion ---

    @Test
    void convertsSrtToWebVtt(@TempDir Path dir) throws Exception {
        Path srt = dir.resolve("movie.srt");
        Files.writeString(srt, "1\r\n00:00:20,000 --> 00:00:24,400\r\nHello there\r\n\r\n",
                StandardCharsets.UTF_8);

        String vtt = subtitles.toWebVtt(srt, "srt");
        assertTrue(vtt.startsWith("WEBVTT"), "missing header: " + vtt);
        // The decimal separator is the difference players actually care about.
        assertTrue(vtt.contains("00:00:20.000 --> 00:00:24.400"), vtt);
        assertTrue(vtt.contains("Hello there"));
        assertFalse(vtt.contains(","), "comma separators should be gone: " + vtt);
    }

    @Test
    void addsHeaderToHeaderlessVtt(@TempDir Path dir) throws Exception {
        Path vttFile = dir.resolve("movie.vtt");
        Files.writeString(vttFile, "00:00:01.000 --> 00:00:02.000\nHi\n");
        assertTrue(subtitles.toWebVtt(vttFile, "vtt").startsWith("WEBVTT"));
    }

    /** Legacy subtitle files are usually Windows-1252, not UTF-8. */
    @Test
    void decodesLegacyEncodedSubtitles(@TempDir Path dir) throws Exception {
        Path srt = dir.resolve("legacy.srt");
        // 0xE9 is 'e-acute' in Windows-1252 and invalid as standalone UTF-8.
        byte[] bytes = new byte[]{
                '1', '\n',
                '0', '0', ':', '0', '0', ':', '0', '1', ',', '0', '0', '0', ' ',
                '-', '-', '>', ' ',
                '0', '0', ':', '0', '0', ':', '0', '2', ',', '0', '0', '0', '\n',
                'C', 'a', 'f', (byte) 0xE9, '\n'};
        Files.write(srt, bytes);

        String vtt = subtitles.toWebVtt(srt, "srt");
        assertTrue(vtt.contains("Café"), "expected fallback decode, got: " + vtt);
        assertFalse(vtt.contains("�"), "should not contain replacement chars: " + vtt);
    }
}
