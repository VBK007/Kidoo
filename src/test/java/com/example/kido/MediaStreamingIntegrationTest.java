package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.Movie;
import com.example.kido.media.catalog.MovieRepository;

/**
 * End-to-end test of the byte-serving layer against a real file inside a real,
 * dynamically configured media root.
 *
 * <p>This is the test that matters most for playback: HTTP range handling is what makes
 * seeking work, and getting {@code 206}/{@code 416}, {@code Content-Range} or the slice
 * boundaries subtly wrong produces video that plays but cannot be scrubbed. The file
 * content is a deterministic byte pattern so every returned slice can be asserted
 * exactly rather than merely by length.
 *
 * <p>No ffmpeg is involved — the fixture is declared as already-probed H.264/AAC in an
 * MP4, which is the direct-play path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaStreamingIntegrationTest {

    private static final int FILE_SIZE = 5000;

    private static Path mediaRoot;
    private static Path movieFile;

    /**
     * Creates the fixture before the context starts, because {@code MediaPaths} resolves
     * and validates the configured roots once, at construction.
     */
    @DynamicPropertySource
    static void configureMediaRoot(DynamicPropertyRegistry registry) {
        try {
            mediaRoot = Files.createTempDirectory("kido-media-root");
            Path folder = Files.createDirectories(mediaRoot.resolve("Test Movie (2020)"));

            movieFile = folder.resolve("Test.Movie.2020.1080p.mp4");
            byte[] payload = new byte[FILE_SIZE];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i % 256);
            }
            Files.write(movieFile, payload);

            Files.write(folder.resolve("poster.jpg"), "FAKE-POSTER-BYTES".getBytes(StandardCharsets.UTF_8));
            Files.writeString(folder.resolve("Test.Movie.2020.1080p.en.srt"),
                    "1\r\n00:00:20,000 --> 00:00:24,400\r\nFirst line\r\n\r\n",
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not build media fixture", ex);
        }
        registry.add("app.media.roots", () -> mediaRoot.toString());
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (mediaRoot == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(mediaRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort on a temp directory.
                }
            });
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    MovieRepository movies;

    private final HttpClient http = HttpClient.newHttpClient();
    private String token;
    private String movieId;

    @BeforeEach
    void setUp() throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = sendString("POST", "/api/auth/register", """
                {"username":"strm_%s","email":"strm_%s@example.com",
                 "password":"pw123456","displayName":"Stream Tester"}
                """.formatted(unique, unique), null);
        assertEquals(201, registered.statusCode(), registered.body());
        token = extract(registered.body(), "token");
        assertNotNull(token);

        // Find-or-create: file_path is unique and the fixture file is shared by every
        // test in this class, so a plain insert would collide after the first one.
        String path = movieFile.toAbsolutePath().normalize().toString();
        Movie movie = movies.findByFilePath(path).orElseGet(() -> movies.save(Movie.builder()
                .filePath(path)
                .fileName(movieFile.getFileName().toString())
                .folderPath(movieFile.getParent().toString())
                .fileSize(FILE_SIZE)
                .title("Test Movie")
                .sortTitle("test movie")
                .year(2020)
                .posterPath(movieFile.getParent().resolve("poster.jpg").toString())
                .mediaInfo(MediaInfo.builder()
                        .container("mov,mp4,m4a")
                        .videoCodec("h264")
                        .audioCodecs("aac")
                        .width(1920)
                        .height(1080)
                        .bitrate(4_000_000L)
                        .durationSeconds(600.0)
                        .probedAt(Instant.now())
                        .build())
                .build()));
        movieId = movie.getId();
    }

    private HttpResponse<String> sendString(String method, String path, String json, String bearer)
            throws Exception {
        return http.send(request(method, path, json, bearer, null).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> sendBytes(String method, String path, String range)
            throws Exception {
        return http.send(request(method, path, null, token, range).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest.Builder request(String method, String path, String json,
                                        String bearer, String range) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (range != null) {
            builder.header("Range", range);
        }
        builder.method(method, json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return builder;
    }

    private static byte[] expectedSlice(int from, int toInclusive) {
        byte[] slice = new byte[toInclusive - from + 1];
        for (int i = 0; i < slice.length; i++) {
            slice[i] = (byte) ((from + i) % 256);
        }
        return slice;
    }

    // --- direct play decision against a real file ---

    @Test
    void decidesDirectPlayAndReturnsStreamUrl() throws Exception {
        HttpResponse<String> response = sendString("POST",
                "/api/media/movies/" + movieId + "/playback-decision", """
                        {"videoCodecs":["h264"],"audioCodecs":["aac"],
                         "containers":["mp4"],"maxHeight":1080,"supportsHls":true}
                        """, token);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"DIRECT\""), response.body());
        assertTrue(response.body().contains("/api/media/movies/" + movieId + "/stream"),
                response.body());
    }

    // --- range serving ---

    @Test
    void servesWholeFileWithoutRangeHeader() throws Exception {
        HttpResponse<byte[]> response = sendBytes("GET", "/api/media/movies/" + movieId + "/stream", null);

        assertEquals(200, response.statusCode());
        assertEquals(FILE_SIZE, response.body().length);
        assertArrayEquals(expectedSlice(0, FILE_SIZE - 1), response.body());
        assertEquals("bytes", response.headers().firstValue("Accept-Ranges").orElse(null));
        assertEquals("video/mp4", response.headers().firstValue("Content-Type").orElse(null));
    }

    @Test
    void servesExactByteRange() throws Exception {
        HttpResponse<byte[]> response =
                sendBytes("GET", "/api/media/movies/" + movieId + "/stream", "bytes=100-199");

        assertEquals(206, response.statusCode());
        assertEquals("bytes 100-199/" + FILE_SIZE,
                response.headers().firstValue("Content-Range").orElse(null));
        assertEquals(100, response.body().length);
        assertArrayEquals(expectedSlice(100, 199), response.body());
    }

    /** The form a player uses when it seeks: open-ended from an offset to the end. */
    @Test
    void servesOpenEndedRangeToEndOfFile() throws Exception {
        HttpResponse<byte[]> response =
                sendBytes("GET", "/api/media/movies/" + movieId + "/stream", "bytes=4900-");

        assertEquals(206, response.statusCode());
        assertEquals("bytes 4900-4999/" + FILE_SIZE,
                response.headers().firstValue("Content-Range").orElse(null));
        assertArrayEquals(expectedSlice(4900, 4999), response.body());
    }

    @Test
    void servesSuffixRange() throws Exception {
        HttpResponse<byte[]> response =
                sendBytes("GET", "/api/media/movies/" + movieId + "/stream", "bytes=-50");

        assertEquals(206, response.statusCode());
        assertEquals(50, response.body().length);
        assertArrayEquals(expectedSlice(FILE_SIZE - 50, FILE_SIZE - 1), response.body());
    }

    /** An end past EOF is clamped rather than refused. */
    @Test
    void clampsRangeEndBeyondFileSize() throws Exception {
        HttpResponse<byte[]> response =
                sendBytes("GET", "/api/media/movies/" + movieId + "/stream", "bytes=4990-99999");

        assertEquals(206, response.statusCode());
        assertEquals("bytes 4990-4999/" + FILE_SIZE,
                response.headers().firstValue("Content-Range").orElse(null));
        assertEquals(10, response.body().length);
    }

    /** A start past EOF is genuinely unsatisfiable and must report the real size. */
    @Test
    void rejectsRangeStartingBeyondFileSize() throws Exception {
        HttpResponse<byte[]> response =
                sendBytes("GET", "/api/media/movies/" + movieId + "/stream", "bytes=99999-");

        assertEquals(416, response.statusCode());
        assertEquals("bytes */" + FILE_SIZE,
                response.headers().firstValue("Content-Range").orElse(null));
    }

    @Test
    void treatsMalformedRangeAsAbsent() throws Exception {
        HttpResponse<byte[]> response =
                sendBytes("GET", "/api/media/movies/" + movieId + "/stream", "bytes=abc-def");

        assertEquals(200, response.statusCode());
        assertEquals(FILE_SIZE, response.body().length);
    }

    // --- artwork and subtitles from the same real folder ---

    @Test
    void servesSidecarPoster() throws Exception {
        HttpResponse<byte[]> response =
                sendBytes("GET", "/api/media/movies/" + movieId + "/poster", null);

        assertEquals(200, response.statusCode());
        assertEquals("FAKE-POSTER-BYTES", new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("image/jpeg", response.headers().firstValue("Content-Type").orElse(null));
    }

    @Test
    void convertsSidecarSubtitleToWebVtt() throws Exception {
        HttpResponse<String> response =
                sendString("GET", "/api/media/movies/" + movieId + "/subtitles/0", null, token);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().startsWith("WEBVTT"), response.body());
        assertTrue(response.body().contains("00:00:20.000 --> 00:00:24.400"), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/vtt"),
                response.headers().map().toString());
    }

    @Test
    void listsSubtitleTrackInDetailResponse() throws Exception {
        HttpResponse<String> response =
                sendString("GET", "/api/media/movies/" + movieId, null, token);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"language\":\"en\""), response.body());
        assertTrue(response.body().contains("\"hasPoster\":true"), response.body());
    }

    @Test
    void streamStillRequiresAuthentication() throws Exception {
        HttpResponse<String> response =
                sendString("GET", "/api/media/movies/" + movieId + "/stream", null, null);
        assertEquals(403, response.statusCode());
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
