package com.example.kido.media.session;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import com.example.kido.media.dto.PlaybackDtos.PlaybackDecisionDto.Mode;

import lombok.Getter;

/**
 * One person watching one thing, right now.
 *
 * <p>Deliberately not persisted. A session is meaningless after a restart — the phone
 * holding it is gone too — and writing a row per progress report would turn playback
 * into a stream of database writes. Watch <em>history</em> is persisted separately as
 * {@link WatchEvent}; this is only the live picture.
 *
 * <p>Mutated from request threads and read from the sampler thread, so counters are
 * atomics and scalars are volatile.
 */
@Getter
public class PlaybackSession {

    private final String id;
    private final String profileId;
    private final String profileName;
    private final String mediaItemId;
    private final String itemTitle;
    private final Mode mode;

    /** Client-declared, e.g. "Arun's iPad" — the admin panel names it. */
    private final String deviceName;
    private final String clientIp;

    /** Set only for transcodes, so the panel can say what is costing CPU. */
    private final String transcodeSessionId;
    private final Integer targetHeight;

    private final Instant startedAt = Instant.now();

    private volatile Instant lastSeenAt = Instant.now();
    private volatile double positionSeconds;
    private volatile Double durationSeconds;
    private volatile boolean terminated;

    /** Total payload bytes written for this session. */
    private final AtomicLong bytesServed = new AtomicLong();

    /** Bytes at the previous sample, so the sampler can difference them. */
    private volatile long sampledBytes;
    private volatile long sampledAtMillis = System.currentTimeMillis();

    /**
     * Smoothed outbound rate in bits per second.
     *
     * <p>Exponentially weighted, because raw per-window rates swing wildly: a player
     * buffers hard for a few seconds then goes quiet, which would otherwise read as
     * "40 Mbps" then "0 Mbps" rather than a usable number.
     */
    private volatile double bitrateBps;

    PlaybackSession(String id,
                    String profileId,
                    String profileName,
                    String mediaItemId,
                    String itemTitle,
                    Mode mode,
                    String deviceName,
                    String clientIp,
                    String transcodeSessionId,
                    Integer targetHeight,
                    Double durationSeconds,
                    double startSeconds) {
        this.id = id;
        this.profileId = profileId;
        this.profileName = profileName;
        this.mediaItemId = mediaItemId;
        this.itemTitle = itemTitle;
        this.mode = mode;
        this.deviceName = deviceName;
        this.clientIp = clientIp;
        this.transcodeSessionId = transcodeSessionId;
        this.targetHeight = targetHeight;
        this.durationSeconds = durationSeconds;
        this.positionSeconds = startSeconds;
    }

    void touch() {
        this.lastSeenAt = Instant.now();
    }

    void addBytes(long count) {
        if (count > 0) {
            bytesServed.addAndGet(count);
            touch();
        }
    }

    void updateProgress(double position, Double duration) {
        this.positionSeconds = position;
        if (duration != null && duration > 0) {
            this.durationSeconds = duration;
        }
        touch();
    }

    void markTerminated() {
        this.terminated = true;
    }

    /** Called by the registry's sampler; folds the latest window into the EWMA. */
    void sampleBitrate(long nowMillis) {
        long total = bytesServed.get();
        long elapsedMillis = nowMillis - sampledAtMillis;
        if (elapsedMillis < 250) {
            return;
        }
        long delta = total - sampledBytes;
        double instantBps = delta * 8_000.0 / elapsedMillis;

        // 0.4 on the new sample: responsive enough to show a stream starting, damped
        // enough that a buffering pause does not read as an idle session.
        this.bitrateBps = bitrateBps <= 0 ? instantBps : (bitrateBps * 0.6) + (instantBps * 0.4);
        this.sampledBytes = total;
        this.sampledAtMillis = nowMillis;
    }

    public long idleMillis() {
        return System.currentTimeMillis() - lastSeenAt.toEpochMilli();
    }

    /** Null when the duration is unknown. */
    public Integer percentComplete() {
        Double duration = durationSeconds;
        if (duration == null || duration <= 0) {
            return null;
        }
        int percent = (int) Math.round(positionSeconds / duration * 100.0);
        return Math.max(0, Math.min(100, percent));
    }
}
