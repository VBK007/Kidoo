package com.example.kido.media.library;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.Getter;

/**
 * Live progress of a library scan, so the app can show something during what may be
 * a multi-minute walk of a large disk.
 *
 * <p>Mutated from the scan thread and read from request threads, so the counters are
 * atomics and the scalar fields are volatile. Nothing here is persisted — a scan does
 * not survive a restart.
 */
@Getter
public class ScanStatus {

    private volatile boolean running;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String currentFile;
    private volatile String error;

    private final AtomicInteger filesSeen = new AtomicInteger();
    private final AtomicInteger added = new AtomicInteger();
    private final AtomicInteger updated = new AtomicInteger();
    private final AtomicInteger unchanged = new AtomicInteger();
    private final AtomicInteger markedMissing = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    synchronized void begin() {
        running = true;
        startedAt = Instant.now();
        finishedAt = null;
        currentFile = null;
        error = null;
        filesSeen.set(0);
        added.set(0);
        updated.set(0);
        unchanged.set(0);
        markedMissing.set(0);
        failed.set(0);
    }

    synchronized void finish(String failureMessage) {
        running = false;
        finishedAt = Instant.now();
        currentFile = null;
        error = failureMessage;
    }

    void setCurrentFile(String file) {
        this.currentFile = file;
    }

    // Package-private mutators keep the counters read-only to callers outside the scanner.
    void countSeen() {
        filesSeen.incrementAndGet();
    }

    void countAdded() {
        added.incrementAndGet();
    }

    void countUpdated() {
        updated.incrementAndGet();
    }

    void countUnchanged() {
        unchanged.incrementAndGet();
    }

    void countMissing(int n) {
        markedMissing.addAndGet(n);
    }

    void countFailed() {
        failed.incrementAndGet();
    }
}
