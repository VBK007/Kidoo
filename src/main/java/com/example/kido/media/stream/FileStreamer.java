package com.example.kido.media.stream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves a file over HTTP, honouring {@code Range} requests.
 *
 * <p>Range support is what makes seeking work: a video player asks for the byte offset
 * it needs rather than downloading from the start, so scrubbing a two-hour film costs
 * one small request. Written by hand rather than delegating to Spring's resource
 * handling because the exact {@code 206}/{@code 416} behaviour and header set is what
 * players are fussy about, and getting it subtly wrong shows up as unseekable video.
 *
 * <p>Only the first range of a multi-range request is served. That is legal — a server
 * may return fewer ranges than asked — and media players only ever send one.
 */
@Slf4j
@Component
public class FileStreamer {

    private static final int BUFFER_BYTES = 64 * 1024;

    /**
     * @param cacheSeconds seconds to allow caching; zero or less sends {@code no-store}
     */
    public long serve(Path file, String contentType, long cacheSeconds,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {

        long length = Files.size(file);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setContentType(contentType);
        response.setHeader(HttpHeaders.CACHE_CONTROL,
                cacheSeconds > 0 ? "private, max-age=" + cacheSeconds : "no-store");

        Range range = parseRange(request.getHeader(HttpHeaders.RANGE), length);

        if (range == null) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentLengthLong(length);
            return copy(file, 0, length, response);
        }

        if (!range.satisfiable()) {
            // 416 must carry the real size so the client can correct itself.
            response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
            response.setContentLength(0);
            return 0;
        }

        long count = range.end() - range.start() + 1;
        response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
        response.setHeader(HttpHeaders.CONTENT_RANGE,
                "bytes " + range.start() + "-" + range.end() + "/" + length);
        response.setContentLengthLong(count);
        return copy(file, range.start(), count, response);
    }

    /**
     * Parses a single byte range.
     *
     * @return null when there is no usable {@code Range} header (serve the whole file),
     *         otherwise a range that may or may not be {@link Range#satisfiable()}
     */
    private static Range parseRange(String header, long length) {
        if (header == null || !header.startsWith("bytes=")) {
            return null;
        }
        String spec = header.substring("bytes=".length()).split(",")[0].trim();
        if (spec.isEmpty()) {
            return null;
        }
        try {
            int dash = spec.indexOf('-');
            if (dash < 0) {
                return null;
            }
            String rawStart = spec.substring(0, dash).trim();
            String rawEnd = spec.substring(dash + 1).trim();

            if (rawStart.isEmpty()) {
                // Suffix form "bytes=-500": the final 500 bytes.
                if (rawEnd.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(rawEnd);
                if (suffix <= 0) {
                    return new Range(0, -1);
                }
                long start = Math.max(0, length - suffix);
                return new Range(start, length - 1);
            }

            long start = Long.parseLong(rawStart);
            if (start >= length) {
                // Unsatisfiable: a player seeking a stale, shorter file lands here.
                return new Range(start, -1);
            }
            long end = rawEnd.isEmpty() ? length - 1 : Math.min(Long.parseLong(rawEnd), length - 1);
            if (end < start) {
                return new Range(start, -1);
            }
            return new Range(start, end);
        } catch (NumberFormatException ex) {
            // A malformed header is treated as absent, per the HTTP spec.
            return null;
        }
    }

    /** @return payload bytes actually written, which is what the session meter counts */
    private long copy(Path file, long start, long count, HttpServletResponse response)
            throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long remaining = count;
        long written = 0;
        try (InputStream in = Files.newInputStream(file)) {
            if (start > 0) {
                in.skipNBytes(start);
            }
            OutputStream out = response.getOutputStream();
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
                written += read;
            }
            out.flush();
        } catch (IOException ex) {
            // Players abort connections constantly — on every seek, pause and app switch.
            // These are normal and must not be logged as server errors or rethrown into
            // the global handler, which would try to write a JSON body onto a dead socket.
            log.debug("Client aborted transfer of {}: {}", file.getFileName(), ex.getMessage());
        }
        return written;
    }

    /** {@code end < 0} marks a range that cannot be satisfied against the current file. */
    private record Range(long start, long end) {
        boolean satisfiable() {
            return end >= start;
        }
    }
}
