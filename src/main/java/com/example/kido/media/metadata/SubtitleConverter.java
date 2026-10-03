package com.example.kido.media.metadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.kido.media.MediaProperties;

/**
 * Converts a subtitle track — external file or embedded stream — to WebVTT, the only
 * format browsers and mobile players accept uniformly.
 *
 * <p>SubRip and WebVTT are nearly the same format; the differences that matter are the
 * required {@code WEBVTT} header and the decimal separator in timestamps
 * ({@code 00:01:02,500} against {@code 00:01:02.500}).
 *
 * <p>Encoding is the messier half. Subtitle files scraped from the internet are
 * frequently Windows-1252 or an ISO-8859 variant rather than UTF-8, and decoding those
 * bytes as UTF-8 yields replacement characters throughout. So a strict UTF-8 decode is
 * attempted first and a single-byte charset is used only when that actually fails.
 */
@Component
public class SubtitleConverter {

    /** Subtitle files are text; anything this large is not one. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    /**
     * An embedded track is a demux, not a re-encode of the picture — this is minutes
     * of margin for a slow disk, not the hours a video transcode might need.
     */
    private static final long EXTRACT_TIMEOUT_SECONDS = 30;

    private final MediaProperties props;

    public SubtitleConverter(MediaProperties props) {
        this.props = props;
    }

    private static final Pattern SRT_TIMECODE = Pattern.compile(
            "(\\d{1,2}:\\d{2}:\\d{2}),(\\d{1,3})\\s*-->\\s*(\\d{1,2}:\\d{2}:\\d{2}),(\\d{1,3})");

    /**
     * @param file   subtitle file, already validated as inside the media roots
     * @param format its extension, which decides whether conversion is needed
     * @return WebVTT text
     * @throws IOException if the file cannot be read
     */
    public String toWebVtt(Path file, String format) throws IOException {
        if (Files.size(file) > MAX_BYTES) {
            throw new IOException("Subtitle file is implausibly large");
        }
        String text = decode(Files.readAllBytes(file));

        if ("vtt".equalsIgnoreCase(format)) {
            // Already WebVTT — but some files omit the mandatory header.
            return text.startsWith("WEBVTT") ? text : "WEBVTT\n\n" + text;
        }
        return srtToVtt(text);
    }

    /**
     * Extracts one embedded subtitle stream straight to WebVTT with ffmpeg.
     *
     * <p>A demux and re-mux, not a re-encode — cheap regardless of the file's length,
     * because ffmpeg never touches the video or audio to get at a subtitle stream.
     *
     * <p>Only a text-based codec ({@code subrip}, {@code mov_text}, {@code ass},
     * {@code webvtt}, ...) can come out the other end this way: a bitmap format like
     * PGS or VobSub has no text for ffmpeg's webvtt muxer to write, and it refuses the
     * conversion outright rather than emit an empty track — that refusal surfaces here
     * as an {@link IOException} with ffmpeg's own stderr, not a silently blank subtitle.
     *
     * @param videoFile    the source file, already validated as inside the media roots
     * @param streamIndex  the track's position in the whole container (ffprobe's own
     *                     {@code index}, as recorded in {@code MediaInfo.embeddedSubtitles}
     *                     and echoed back to the client in {@code SubtitleTrackDto.index}
     *                     for an embedded track) — not a position among subtitle streams
     *                     only, so this is mapped as a bare {@code 0:N} stream specifier
     * @return WebVTT text
     */
    public String extractEmbeddedWebVtt(Path videoFile, int streamIndex) throws IOException {
        List<String> command = List.of(
                props.getFfmpegPath(), "-hide_banner", "-loglevel", "error", "-nostdin",
                "-i", videoFile.toString(),
                "-map", "0:" + streamIndex,
                "-c:s", "webvtt",
                "-f", "webvtt", "pipe:1");

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException ex) {
            throw new IOException("Could not start ffmpeg: " + ex.getMessage(), ex);
        }

        // Both pipes have to be drained concurrently, or a chatty stderr (ffmpeg's own
        // complaint about a bitmap codec, say) fills its buffer and deadlocks the
        // process before stdout ever reaches EOF — the same reason
        // TranscodeSessionManager redirects its stderr to a file instead of a pipe.
        // A short-lived stream has nothing worth logging on success, so a thread that
        // just drains it is simpler than that.
        byte[][] stderrBox = new byte[1][];
        Thread stderrReader = new Thread(() -> {
            try (var err = process.getErrorStream()) {
                stderrBox[0] = err.readAllBytes();
            } catch (IOException ignored) {
                stderrBox[0] = new byte[0];
            }
        });
        stderrReader.start();

        byte[] vtt;
        try (var out = process.getInputStream()) {
            vtt = out.readAllBytes();
        }

        boolean finished;
        try {
            finished = process.waitFor(EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stderrReader.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while extracting subtitles", ex);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Subtitle extraction timed out after " + EXTRACT_TIMEOUT_SECONDS + "s");
        }
        if (process.exitValue() != 0 || vtt.length == 0) {
            byte[] stderr = stderrBox[0];
            String reason = stderr != null && stderr.length > 0
                    ? new String(stderr, StandardCharsets.UTF_8).trim()
                    : "ffmpeg exited " + process.exitValue() + " with no output";
            throw new IOException("Could not extract subtitle stream " + streamIndex + ": " + reason);
        }
        return new String(vtt, StandardCharsets.UTF_8);
    }

    private static String srtToVtt(String srt) {
        StringBuilder out = new StringBuilder(srt.length() + 16);
        out.append("WEBVTT\n\n");

        // Normalise line endings first: CRLF is the norm in SRT and confuses cue parsing.
        String normalised = srt.replace("\r\n", "\n").replace('\r', '\n');

        Matcher matcher = SRT_TIMECODE.matcher(normalised);
        int last = 0;
        while (matcher.find()) {
            out.append(normalised, last, matcher.start());
            out.append(matcher.group(1)).append('.').append(pad(matcher.group(2)))
                    .append(" --> ")
                    .append(matcher.group(3)).append('.').append(pad(matcher.group(4)));
            last = matcher.end();
        }
        out.append(normalised.substring(last));
        return out.toString();
    }

    /** WebVTT requires exactly three decimal places on the fractional second. */
    private static String pad(String millis) {
        if (millis.length() == 3) {
            return millis;
        }
        return (millis + "000").substring(0, 3);
    }

    /**
     * Decodes as UTF-8 when the bytes really are UTF-8, else as Windows-1252.
     *
     * <p>A strict decoder is used deliberately: the lenient default silently substitutes
     * U+FFFD, which would make every accented character in a legacy-encoded file
     * unreadable rather than triggering the fallback.
     */
    private static String decode(byte[] raw) {
        int offset = 0;
        if (raw.length >= 3
                && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw, offset, raw.length - offset);

        CharsetDecoder strictUtf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return strictUtf8.decode(buffer).toString();
        } catch (CharacterCodingException ex) {
            buffer.rewind();
            // windows-1252 is the de facto encoding of legacy subtitle files and, being
            // single-byte, cannot itself fail to decode.
            Charset fallback = Charset.isSupported("windows-1252")
                    ? Charset.forName("windows-1252")
                    : StandardCharsets.ISO_8859_1;
            return fallback.decode(buffer).toString();
        }
    }
}
