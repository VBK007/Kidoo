package com.example.kido.media.stream;

import java.nio.file.Path;
import java.time.Instant;

import lombok.Getter;

/**
 * One running ffmpeg transcode, its scratch directory and its liveness.
 *
 * <p>{@code lastAccessAt} is bumped on every playlist and segment request and is what
 * the reaper uses to decide a session has been abandoned. It has to be volatile: it is
 * written from request threads and read from the reaper thread.
 */
@Getter
public class TranscodeSession {

    public enum State {
        STARTING, READY, FAILED, STOPPED
    }

    private final String id;
    private final String movieId;
    private final Path directory;
    private final Path playlist;
    private final Path ffmpegLog;
    private final double startSeconds;
    private final int height;
    private final Process process;
    private final Instant createdAt = Instant.now();

    private volatile Instant lastAccessAt = Instant.now();
    private volatile State state = State.STARTING;

    TranscodeSession(String id, String movieId, Path directory, double startSeconds,
                     int height, Process process) {
        this.id = id;
        this.movieId = movieId;
        this.directory = directory;
        this.playlist = directory.resolve(TranscodeSessionManager.PLAYLIST_NAME);
        this.ffmpegLog = directory.resolve("ffmpeg.log");
        this.startSeconds = startSeconds;
        this.height = height;
        this.process = process;
    }

    void touch() {
        this.lastAccessAt = Instant.now();
    }

    void setState(State state) {
        this.state = state;
    }

    public boolean isProcessAlive() {
        return process != null && process.isAlive();
    }

    /**
     * True once ffmpeg has exited normally — the whole remainder of the film has been
     * written, so the session is still perfectly usable for playback and must not be
     * reaped merely because the process is gone.
     */
    public boolean isComplete() {
        return process != null && !process.isAlive() && process.exitValue() == 0;
    }
}
