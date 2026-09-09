package com.example.kido.media.stream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.Movie;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Starts, tracks and reaps on-the-fly HLS transcodes.
 *
 * <p>Each session is one ffmpeg process writing MPEG-TS segments plus a growing
 * playlist into its own scratch directory. The client is handed the playlist URL and
 * fetches segments through this server, so ffmpeg's relative segment names resolve
 * against our own endpoint and the playlist needs no rewriting.
 *
 * <p>Three things here exist because ffmpeg is an external process that will otherwise
 * leak: a concurrency cap (each transcode saturates several cores), an idle reaper
 * (mobile clients vanish without closing anything), and a shutdown hook. Segment names
 * are pattern-validated before being resolved against the session directory, since they
 * arrive from the URL.
 *
 * <h2>Known limitation</h2>
 * ffmpeg is not rate-limited, so it transcodes ahead as fast as the CPU allows and a
 * long film can occupy a few GB of {@code app.media.transcode-dir} until the session is
 * reaped. That is a deliberate trade for fast startup and instant in-buffer seeking;
 * tune {@code max-transcode-sessions} and the idle timeout if scratch space is tight.
 */
@Slf4j
@Service
public class TranscodeSessionManager {

    static final String PLAYLIST_NAME = "index.m3u8";

    /** Segment names ffmpeg is told to produce — anything else is rejected unresolved. */
    private static final Pattern SEGMENT_NAME = Pattern.compile("seg_\\d{5}\\.ts");

    /** How long to wait for ffmpeg to produce a playable first segment. */
    private static final long STARTUP_TIMEOUT_MS = 30_000;
    private static final long STARTUP_POLL_MS = 200;

    private final MediaProperties props;
    private final Map<String, TranscodeSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "media-transcode-reaper");
                thread.setDaemon(true);
                return thread;
            });

    private Path rootDir;

    public TranscodeSessionManager(MediaProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        rootDir = Path.of(props.getTranscodeDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootDir);
            // Anything already here is from a previous run whose sessions are long dead.
            purgeStaleDirectories();
        } catch (IOException ex) {
            log.warn("Could not prepare transcode dir {}: {}", rootDir, ex.getMessage());
        }
        reaper.scheduleWithFixedDelay(this::reapIdleSessions, 30, 30, TimeUnit.SECONDS);
        log.info("Transcode scratch space at {} (max {} concurrent sessions)",
                rootDir, props.getMaxTranscodeSessions());
    }

    /**
     * Launches a transcode and blocks until the first segment is playable.
     *
     * @param startSeconds offset to seek to before encoding; the produced stream always
     *                     starts at zero, so the client applies this offset itself
     * @throws ApiException 429 when all session slots are busy, 500 if ffmpeg will not start
     */
    public TranscodeSession start(Movie movie, Path file, double startSeconds, int height) {
        evictFinishedSessions();
        if (sessions.size() >= props.getMaxTranscodeSessions()) {
            // Better an explicit refusal than thrashing the CPU and stalling every stream.
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "All transcode slots are busy (" + props.getMaxTranscodeSessions()
                            + "); try again shortly or play a directly-playable title");
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "");
        Path directory = rootDir.resolve(sessionId);
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not create transcode workspace");
        }

        List<String> command = buildCommand(file, directory, startSeconds, height);
        log.info("Starting transcode session={} movie={} start={}s height={}p",
                sessionId, movie.getId(), startSeconds, height);
        log.debug("ffmpeg command: {}", String.join(" ", command));

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    // To a file, not a pipe: an undrained stderr pipe fills and deadlocks ffmpeg.
                    .redirectError(directory.resolve("ffmpeg.log").toFile())
                    .start();
        } catch (IOException ex) {
            deleteQuietly(directory);
            log.error("Could not start ffmpeg ({}): {}", props.getFfmpegPath(), ex.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Transcoding is unavailable: ffmpeg could not be started");
        }

        TranscodeSession session =
                new TranscodeSession(sessionId, movie.getId(), directory, startSeconds, height, process);
        sessions.put(sessionId, session);

        if (!awaitFirstSegment(session)) {
            String detail = tailFfmpegLog(session);
            stop(sessionId);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Transcode failed to start" + (detail.isBlank() ? "" : ": " + detail));
        }
        session.setState(TranscodeSession.State.READY);
        return session;
    }

    private List<String> buildCommand(Path file, Path directory, double startSeconds, int height) {
        List<String> command = new ArrayList<>();
        command.add(props.getFfmpegPath());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");

        // Before -i, so ffmpeg seeks by keyframe index instead of decoding and discarding
        // everything up to the offset. Output timestamps then restart at zero.
        if (startSeconds > 0) {
            command.add("-ss");
            command.add(String.format(java.util.Locale.ROOT, "%.3f", startSeconds));
        }
        command.add("-i");
        command.add(file.toString());

        command.add("-map");
        command.add("0:v:0");
        // The trailing '?' makes the audio stream optional so a silent file still encodes.
        command.add("-map");
        command.add("0:a:0?");
        command.add("-sn");
        command.add("-dn");

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add(props.getTranscodePreset());
        command.add("-crf");
        command.add(String.valueOf(props.getTranscodeCrf()));
        // Constrain to what mobile hardware decoders accept.
        command.add("-profile:v");
        command.add("high");
        command.add("-level");
        command.add("4.1");
        command.add("-pix_fmt");
        command.add("yuv420p");
        // -2 keeps the aspect ratio and rounds width to an even number, which H.264 requires.
        // The height is already clamped to the source, so this never upscales.
        command.add("-vf");
        command.add("scale=-2:" + height);
        // Segment boundaries must land on keyframes for seeking to work.
        command.add("-force_key_frames");
        command.add("expr:gte(t,n_forced*" + props.getHlsSegmentSeconds() + ")");

        command.add("-c:a");
        command.add("aac");
        command.add("-ac");
        command.add("2");
        command.add("-b:a");
        command.add("160k");

        command.add("-f");
        command.add("hls");
        command.add("-hls_time");
        command.add(String.valueOf(props.getHlsSegmentSeconds()));
        // 'event' lets the playlist grow while the client plays; 'vod' would need the
        // whole encode finished before the first byte could be served.
        command.add("-hls_playlist_type");
        command.add("event");
        command.add("-hls_list_size");
        command.add("0");
        command.add("-hls_flags");
        command.add("independent_segments+temp_file");
        command.add("-hls_segment_filename");
        command.add(directory.resolve("seg_%05d.ts").toString());
        command.add(directory.resolve(PLAYLIST_NAME).toString());
        return command;
    }

    /**
     * Waits for a playlist that references at least one segment.
     *
     * <p>The playlist file appears almost immediately but is briefly empty, and handing
     * an HLS client an empty playlist makes it give up rather than retry.
     */
    private boolean awaitFirstSegment(TranscodeSession session) {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(session.getPlaylist())) {
                try {
                    String content = Files.readString(session.getPlaylist(), StandardCharsets.UTF_8);
                    if (content.contains(".ts")) {
                        return true;
                    }
                } catch (IOException ignored) {
                    // Mid-write; try again on the next tick.
                }
            }
            if (!session.isProcessAlive()) {
                // ffmpeg exited before producing anything — bad input or bad arguments.
                return false;
            }
            try {
                Thread.sleep(STARTUP_POLL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("Transcode session={} produced no segment within {}ms",
                session.getId(), STARTUP_TIMEOUT_MS);
        return false;
    }

    /** @throws ApiException 404 when the session has been reaped or never existed */
    public TranscodeSession require(String sessionId) {
        TranscodeSession session = sessions.get(sessionId);
        if (session == null) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "Transcode session not found; request a new playback decision");
        }
        session.touch();
        return session;
    }

    public Path playlistFile(String sessionId) {
        return require(sessionId).getPlaylist();
    }

    /**
     * Resolves a segment inside the session directory.
     *
     * <p>The name is matched against {@link #SEGMENT_NAME} before it is resolved, so a
     * traversal attempt never reaches the filesystem.
     */
    public Path segmentFile(String sessionId, String segmentName) {
        TranscodeSession session = require(sessionId);
        if (segmentName == null || !SEGMENT_NAME.matcher(segmentName).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid segment name");
        }
        Path segment = session.getDirectory().resolve(segmentName).normalize();
        if (!segment.startsWith(session.getDirectory())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid segment name");
        }
        if (!Files.isRegularFile(segment)) {
            // Requested faster than ffmpeg is producing; the client should retry.
            throw new ApiException(HttpStatus.NOT_FOUND, "Segment not ready yet");
        }
        return segment;
    }

    public void stop(String sessionId) {
        TranscodeSession session = sessions.remove(sessionId);
        if (session == null) {
            return;
        }
        session.setState(TranscodeSession.State.STOPPED);
        killProcess(session);
        deleteQuietly(session.getDirectory());
        log.info("Stopped transcode session={}", sessionId);
    }

    public List<TranscodeSession> activeSessions() {
        return List.copyOf(sessions.values());
    }

    /** Frees slots held by sessions whose client has gone away. */
    private void reapIdleSessions() {
        try {
            long idleLimit = props.getTranscodeIdleTimeoutSeconds() * 1000L;
            long now = System.currentTimeMillis();
            for (TranscodeSession session : List.copyOf(sessions.values())) {
                long idleMs = now - session.getLastAccessAt().toEpochMilli();
                boolean died = !session.isProcessAlive() && !session.isComplete();
                if (idleMs > idleLimit) {
                    log.info("Reaping transcode session={} idle for {}ms", session.getId(), idleMs);
                    stop(session.getId());
                } else if (died) {
                    log.warn("Transcode session={} process died: {}",
                            session.getId(), tailFfmpegLog(session));
                    session.setState(TranscodeSession.State.FAILED);
                    stop(session.getId());
                }
            }
        } catch (Exception ex) {
            // The reaper must never die, or every later session leaks.
            log.error("Transcode reaper pass failed", ex);
        }
    }

    /** Drops completed-and-idle sessions so a new request is not refused for nothing. */
    private void evictFinishedSessions() {
        List<TranscodeSession> candidates = new ArrayList<>(sessions.values());
        candidates.sort(Comparator.comparing(TranscodeSession::getLastAccessAt));
        for (TranscodeSession session : candidates) {
            if (sessions.size() < props.getMaxTranscodeSessions()) {
                return;
            }
            if (!session.isProcessAlive() && !session.isComplete()) {
                stop(session.getId());
            }
        }
    }

    private void killProcess(TranscodeSession session) {
        Process process = session.getProcess();
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** ffmpeg errors are the only useful diagnostic when a transcode will not start. */
    private String tailFfmpegLog(TranscodeSession session) {
        try {
            if (!Files.isRegularFile(session.getFfmpegLog())) {
                return "";
            }
            String content = Files.readString(session.getFfmpegLog(), StandardCharsets.UTF_8).trim();
            return content.length() <= 300 ? content : content.substring(content.length() - 300);
        } catch (IOException ex) {
            return "";
        }
    }

    private void purgeStaleDirectories() throws IOException {
        try (Stream<Path> children = Files.list(rootDir)) {
            children.filter(Files::isDirectory).forEach(this::deleteQuietly);
        }
    }

    private void deleteQuietly(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A segment still being written by a dying ffmpeg; the next
                    // startup purge will clear it.
                }
            });
        } catch (IOException ex) {
            log.debug("Could not fully delete {}: {}", directory, ex.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        reaper.shutdownNow();
        for (String sessionId : List.copyOf(sessions.keySet())) {
            stop(sessionId);
        }
    }
}
