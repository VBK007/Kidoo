package com.example.kido.media.probe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.example.kido.media.MediaProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Wraps {@code ffprobe} to read a still image's pixel dimensions — the same binary the
 * video probe already shells out to, and the only thing here that reliably reads WebP
 * and AVIF without pulling in a dedicated image library, since the JDK's own
 * {@code ImageIO} supports neither out of the box and both show up as poster formats.
 */
@Slf4j
@Component
public class ImageProbe {

    private final MediaProperties props;

    public ImageProbe(MediaProperties props) {
        this.props = props;
    }

    public record Dimensions(int width, int height) {
        public boolean atLeast(int minWidth, int minHeight) {
            return width >= minWidth && height >= minHeight;
        }
    }

    /** @return dimensions, or empty if ffprobe is unavailable, times out or the file is unreadable */
    public Optional<Dimensions> dimensions(Path image) {
        List<String> command = List.of(
                props.getFfprobePath(),
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height",
                "-of", "csv=p=0:s=x",
                image.toString());

        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // Read before waiting, same reasoning as MediaProbe: a full pipe buffer
            // would otherwise deadlock against a process we are waiting to exit.
            String output = readAll(process.getInputStream());

            if (!process.waitFor(props.getProbeTimeoutSeconds(), TimeUnit.SECONDS)) {
                log.debug("ffprobe timed out on image {}", image);
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            return parse(output.trim());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception ex) {
            log.debug("ffprobe failed for image {}: {}", image, ex.getMessage());
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static Optional<Dimensions> parse(String line) {
        int x = line.indexOf('x');
        if (x <= 0 || x == line.length() - 1) {
            return Optional.empty();
        }
        try {
            int width = Integer.parseInt(line.substring(0, x));
            int height = Integer.parseInt(line.substring(x + 1));
            return Optional.of(new Dimensions(width, height));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
