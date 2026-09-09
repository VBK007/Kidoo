package com.example.kido.media.probe;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaInfo;

import lombok.extern.slf4j.Slf4j;

/**
 * Wraps {@code ffprobe} to read the codecs and dimensions inside a container.
 *
 * <p>This is the input to every playback decision: a filename says nothing reliable
 * about whether a phone can decode the file, and the extension says little more — an
 * {@code .mp4} may hold HEVC that an older Android cannot play.
 *
 * <p>Output is requested in ffprobe's flat {@code default} writer format rather than
 * JSON, deliberately. It is a stable, trivially parsed {@code key=value} format, and
 * using it keeps this class independent of whichever Jackson major version Spring Boot
 * currently ships — the databind package name and node accessors changed between
 * Jackson 2 and 3, and a probe is not worth that coupling.
 *
 * <p>The process is bounded by {@code app.media.probe-timeout-seconds} and destroyed on
 * timeout, since a truncated or corrupt file can otherwise make ffprobe hang and stall
 * an entire library scan.
 */
@Slf4j
@Component
public class MediaProbe {

    /** Exactly the fields the playback decision needs — nothing else is worth parsing. */
    private static final String ENTRIES =
            "format=format_name,duration,bit_rate,size"
                    + ":stream=index,codec_type,codec_name,profile,width,height,channels,bit_rate"
                    + ":stream_tags=language";

    private final MediaProperties props;

    public MediaProbe(MediaProperties props) {
        this.props = props;
    }

    /** @return probe results, or empty if ffprobe is unavailable, timed out or failed */
    public Optional<MediaInfo> probe(Path file) {
        List<String> command = List.of(
                props.getFfprobePath(),
                "-v", "error",
                "-show_entries", ENTRIES,
                "-of", "default",
                file.toString());

        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    // Merged so a diagnostic on stderr cannot fill an undrained pipe and
                    // deadlock the process; error lines simply fail to parse as key=value.
                    .redirectErrorStream(true)
                    .start();

            // Read before waiting: ffprobe writes several KB and would block on a full
            // pipe buffer if we waited for exit first.
            Sections sections = readSections(process.getInputStream());

            if (!process.waitFor(props.getProbeTimeoutSeconds(), TimeUnit.SECONDS)) {
                log.warn("ffprobe timed out after {}s on {}", props.getProbeTimeoutSeconds(), file);
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                log.warn("ffprobe exited {} for {}", process.exitValue(), file);
                return Optional.empty();
            }
            if (sections.format.isEmpty() && sections.streams.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(toMediaInfo(sections));

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception ex) {
            // Overwhelmingly the common case: ffprobe is not installed or not on PATH.
            log.warn("ffprobe failed for {}: {}", file, ex.getMessage());
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Parses the {@code default} writer's sectioned output:
     * <pre>
     * [STREAM]
     * index=0
     * codec_type=video
     * TAG:language=eng
     * [/STREAM]
     * [FORMAT]
     * duration=8580.123000
     * [/FORMAT]
     * </pre>
     */
    private static Sections readSections(InputStream in) throws Exception {
        Sections sections = new Sections();
        Map<String, String> current = null;

        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.equals("[STREAM]")) {
                    current = new HashMap<>();
                    sections.streams.add(current);
                    continue;
                }
                if (trimmed.equals("[FORMAT]")) {
                    current = sections.format;
                    continue;
                }
                if (trimmed.startsWith("[/")) {
                    current = null;
                    continue;
                }
                if (current == null) {
                    continue;
                }
                int equals = trimmed.indexOf('=');
                if (equals > 0) {
                    current.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
                }
            }
        }
        return sections;
    }

    private MediaInfo toMediaInfo(Sections sections) {
        Map<String, String> video = null;
        List<String> audioCodecs = new ArrayList<>();
        Integer audioChannels = null;
        List<String> subtitles = new ArrayList<>();

        for (Map<String, String> stream : sections.streams) {
            String type = stream.getOrDefault("codec_type", "");
            switch (type) {
                case "video" -> {
                    // Cover art also reports as a video stream, so take the first one
                    // that actually has dimensions.
                    if (video == null && intOf(stream.get("width")) != null) {
                        video = stream;
                    }
                }
                case "audio" -> {
                    audioCodecs.add(stream.getOrDefault("codec_name", "unknown"));
                    if (audioChannels == null) {
                        audioChannels = intOf(stream.get("channels"));
                    }
                }
                case "subtitle" -> subtitles.add(
                        stream.getOrDefault("index", "-1")
                                + ":" + stream.getOrDefault("codec_name", "unknown")
                                + ":" + stream.getOrDefault("TAG:language", "und"));
                default -> { /* attachments and data streams are not relevant */ }
            }
        }

        // Container-level bitrate is absent from some MKVs; fall back to the video stream.
        Long bitrate = longOf(sections.format.get("bit_rate"));
        if (bitrate == null && video != null) {
            bitrate = longOf(video.get("bit_rate"));
        }

        MediaInfo.MediaInfoBuilder builder = MediaInfo.builder()
                .container(blankToNull(sections.format.get("format_name")))
                .durationSeconds(doubleOf(sections.format.get("duration")))
                .bitrate(bitrate)
                .audioCodecs(audioCodecs.isEmpty() ? null : String.join(",", audioCodecs))
                .audioChannels(audioChannels)
                .embeddedSubtitles(subtitles.isEmpty()
                        ? null
                        : truncate(String.join(";", subtitles), 2000))
                .probedAt(Instant.now());

        if (video != null) {
            builder.videoCodec(blankToNull(video.get("codec_name")))
                    .videoProfile(blankToNull(video.get("profile")))
                    .width(intOf(video.get("width")))
                    .height(intOf(video.get("height")));
        }
        return builder.build();
    }

    /** ffprobe writes "N/A" for fields it could not determine. */
    private static String blankToNull(String value) {
        if (value == null || value.isBlank() || value.equals("N/A") || value.equals("unknown")) {
            return null;
        }
        return value;
    }

    private static Integer intOf(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long longOf(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double doubleOf(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** One {@code [FORMAT]} block and any number of {@code [STREAM]} blocks. */
    private static final class Sections {
        private final Map<String, String> format = new HashMap<>();
        private final List<Map<String, String>> streams = new ArrayList<>();
    }
}
