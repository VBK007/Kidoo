package com.example.kido.media.music;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.example.kido.media.MediaProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs the bundled {@code audio_features.py} against one track and parses its result —
 * tempo, RMS energy, spectral centroid — the way {@link
 * com.example.kido.media.probe.MediaProbe} already shells out to {@code ffprobe}.
 *
 * <p>The script is bundled as a classpath resource rather than assumed to exist on the
 * host, and extracted to a fixed path once at startup so every invocation reuses the
 * same file instead of re-extracting it per track.
 */
@Slf4j
@Component
public class AudioFeatureService {

    /**
     * Deliberately not JSON-parsed with a library: the script emits one flat, known
     * shape, and matching the four fields with regexes avoids pulling the app's Jackson
     * dependency into a class that would otherwise have none.
     */
    private static final Pattern NUMBER_FIELD = Pattern.compile(
            "\"(bpm|energy_rms|spectral_centroid)\"\\s*:\\s*(-?[0-9.]+|null)");

    private final MediaProperties.AudioAnalysis config;
    private final Path scriptPath;

    public AudioFeatureService(MediaProperties props) {
        this.config = props.getAudioAnalysis();
        this.scriptPath = extractScript(config.getScriptResource());
    }

    public record Features(Double bpm, Double energyRms, Double spectralCentroid) {}

    /** @return features, or empty if analysis is disabled, the script is missing, or decode failed */
    public Optional<Features> analyze(Path file) {
        if (!config.isEnabled() || scriptPath == null) {
            return Optional.empty();
        }
        try {
            Process process = new ProcessBuilder(
                    config.getPythonPath(), scriptPath.toString(),
                    file.toString(), String.valueOf(config.getAnalyzeSeconds()))
                    .redirectErrorStream(false)
                    .start();

            String output = readAll(process.getInputStream());
            boolean finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("Audio analysis timed out for '{}'", file);
                return Optional.empty();
            }
            return parse(output, file);
        } catch (IOException ex) {
            log.debug("Audio analysis failed to start for '{}': {}", file, ex.getMessage());
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private Optional<Features> parse(String output, Path file) {
        Double bpm = null;
        Double energy = null;
        Double centroid = null;
        Matcher m = NUMBER_FIELD.matcher(output);
        while (m.find()) {
            Double value = "null".equals(m.group(2)) ? null : Double.valueOf(m.group(2));
            switch (m.group(1)) {
                case "bpm" -> bpm = value;
                case "energy_rms" -> energy = value;
                case "spectral_centroid" -> centroid = value;
                default -> { }
            }
        }
        if (energy == null) {
            log.debug("Audio analysis produced no usable energy reading for '{}'", file);
            return Optional.empty();
        }
        return Optional.of(new Features(bpm, energy, centroid));
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /** @return the extracted script's path, or null if the resource could not be extracted */
    private static Path extractScript(String resourcePath) {
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            Path target = Files.createTempDirectory("kido-audio-features")
                    .resolve("audio_features.py");
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException ex) {
            log.warn("Could not extract bundled audio_features.py; mood/activity rails "
                    + "will stay empty until this is fixed: {}", ex.getMessage());
            return null;
        }
    }
}
