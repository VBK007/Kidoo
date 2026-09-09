package com.example.kido.media.session;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.dto.PlaybackDtos.PlaybackDecisionDto.Mode;
import com.example.kido.media.stream.TranscodeSessionManager;
import com.example.kido.profile.Profile;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * The live view of who is watching what — what the admin panel's Health and People
 * tabs are built on, and what makes "end this session" possible.
 *
 * <p>A session is opened by a playback decision and kept alive by the bytes the client
 * subsequently pulls. That is the only honest liveness signal available: HTTP range
 * requests carry no session concept of their own, and a phone that goes into a tunnel
 * never tells the server anything.
 *
 * <h2>What ending a session can and cannot do</h2>
 * For a transcode, ending it kills the ffmpeg process and playback stops within a
 * segment. For direct play there is no process to kill and the response already in
 * flight cannot be recalled — so termination is enforced on the <em>next</em> range
 * request instead, which for a player pulling every few seconds means playback stops
 * almost immediately. This is why the decision hands back a stream URL carrying a
 * {@code session} parameter; without it, a direct play is untrackable and unstoppable.
 */
@Slf4j
@Service
public class PlaybackSessionRegistry {

    /** No bytes and no progress for this long and the viewer is assumed gone. */
    private static final long IDLE_TIMEOUT_MILLIS = 90_000;

    /** How often bitrates are sampled and dead sessions swept. */
    private static final long SAMPLE_INTERVAL_SECONDS = 5;

    private final TranscodeSessionManager transcodes;

    private final Map<String, PlaybackSession> sessions = new ConcurrentHashMap<>();

    /**
     * High-water mark of concurrent sessions since startup. Backs the panel's
     * "cap concurrent streams at 2?" suggestion, which needs evidence to be worth
     * showing at all.
     */
    private final AtomicInteger peakConcurrent = new AtomicInteger();

    private final ScheduledExecutorService sampler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "media-session-sampler");
                thread.setDaemon(true);
                return thread;
            });

    public PlaybackSessionRegistry(TranscodeSessionManager transcodes) {
        this.transcodes = transcodes;
    }

    @PostConstruct
    void init() {
        sampler.scheduleWithFixedDelay(this::sampleAndSweep,
                SAMPLE_INTERVAL_SECONDS, SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Opens a session for a playback decision.
     *
     * <p>Any existing session for the same profile and item is replaced: re-deciding
     * is what a client does when it seeks past the transcoded region or restarts
     * playback, and two rows for one viewer would double-count the People tab.
     */
    public PlaybackSession start(Profile profile,
                                 MediaItem item,
                                 Mode mode,
                                 String deviceName,
                                 String clientIp,
                                 String transcodeSessionId,
                                 Integer targetHeight,
                                 double startSeconds) {

        sessions.values().stream()
                .filter(existing -> existing.getProfileId().equals(profile.getId())
                        && existing.getMediaItemId().equals(item.getId()))
                .map(PlaybackSession::getId)
                .toList()
                .forEach(this::discard);

        PlaybackSession session = new PlaybackSession(
                UUID.randomUUID().toString().replace("-", ""),
                profile.getId(),
                profile.getName(),
                item.getId(),
                item.getTitle(),
                mode,
                deviceName == null || deviceName.isBlank() ? "Unknown device" : deviceName.trim(),
                clientIp,
                transcodeSessionId,
                targetHeight,
                item.getMediaInfo() == null ? null : item.getMediaInfo().getDurationSeconds(),
                startSeconds);

        sessions.put(session.getId(), session);
        peakConcurrent.accumulateAndGet(sessions.size(), Math::max);
        log.info("Playback session={} profile='{}' item='{}' mode={} device='{}'",
                session.getId(), profile.getName(), item.getTitle(), mode, session.getDeviceName());
        return session;
    }

    public Optional<PlaybackSession> find(String sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * Confirms a session may still be served.
     *
     * @return false when the session was ended by the owner — the caller should refuse
     *         the request rather than keep feeding a stream someone stopped
     */
    public boolean isServable(String sessionId) {
        // Guarded before the lookup: ConcurrentHashMap throws on a null key, and
        // streaming without a session is a supported case rather than an error.
        if (sessionId == null || sessionId.isBlank()) {
            return true;
        }
        PlaybackSession session = sessions.get(sessionId);
        if (session == null) {
            // Unknown ids are allowed through: a client may stream without a session,
            // and an expired session should not break playback that is still going.
            return true;
        }
        return !session.isTerminated();
    }

    /** Records payload bytes against a session, which also keeps it alive. */
    public void recordBytes(String sessionId, long bytes) {
        if (sessionId == null || bytes <= 0) {
            return;
        }
        PlaybackSession session = sessions.get(sessionId);
        if (session != null) {
            session.addBytes(bytes);
        }
    }

    /**
     * Records segment bytes against whichever playback session owns a transcode.
     *
     * <p>HLS requests carry the ffmpeg session id in the path, not the playback session
     * id, so the lookup goes the other way round here.
     */
    public void recordBytesForTranscode(String transcodeSessionId, long bytes) {
        if (transcodeSessionId == null || bytes <= 0) {
            return;
        }
        for (PlaybackSession session : sessions.values()) {
            if (transcodeSessionId.equals(session.getTranscodeSessionId())) {
                session.addBytes(bytes);
                return;
            }
        }
    }

    /** Folds a client progress report into whichever session matches. */
    public void recordProgress(String profileId, String itemId, double position, Double duration) {
        for (PlaybackSession session : sessions.values()) {
            if (session.getProfileId().equals(profileId)
                    && session.getMediaItemId().equals(itemId)) {
                session.updateProgress(position, duration);
                return;
            }
        }
    }

    /**
     * Ends a session on the owner's instruction.
     *
     * <p>The row is kept, marked terminated, so a direct-play client's next range
     * request can be refused. The sweeper removes it once the client stops asking.
     */
    public boolean terminate(String sessionId) {
        PlaybackSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        session.markTerminated();
        if (session.getTranscodeSessionId() != null) {
            transcodes.stop(session.getTranscodeSessionId());
        }
        log.info("Terminated playback session={} profile='{}' item='{}'",
                sessionId, session.getProfileName(), session.getItemTitle());
        return true;
    }

    /** Removes a session without the termination semantics, e.g. when superseded. */
    public void discard(String sessionId) {
        PlaybackSession removed = sessions.remove(sessionId);
        if (removed != null && removed.getTranscodeSessionId() != null) {
            transcodes.stop(removed.getTranscodeSessionId());
        }
    }

    /** Active sessions, newest first. */
    public List<PlaybackSession> active() {
        return sessions.values().stream()
                .sorted(Comparator.comparing(PlaybackSession::getStartedAt).reversed())
                .toList();
    }

    public int activeCount() {
        return sessions.size();
    }

    public int peakConcurrent() {
        return peakConcurrent.get();
    }

    /** Total outbound bits per second across every session — "out the door". */
    public double totalBitrateBps() {
        return sessions.values().stream().mapToDouble(PlaybackSession::getBitrateBps).sum();
    }

    public long transcodingCount() {
        return sessions.values().stream().filter(s -> s.getMode() == Mode.TRANSCODE).count();
    }

    private void sampleAndSweep() {
        try {
            long now = System.currentTimeMillis();
            for (PlaybackSession session : List.copyOf(sessions.values())) {
                session.sampleBitrate(now);
                if (session.idleMillis() > IDLE_TIMEOUT_MILLIS) {
                    log.debug("Sweeping idle playback session={} ({}ms idle)",
                            session.getId(), session.idleMillis());
                    discard(session.getId());
                }
            }
        } catch (Exception ex) {
            // The sampler must never die, or every later session leaks.
            log.error("Session sampler pass failed", ex);
        }
    }

    @PreDestroy
    void shutdown() {
        sampler.shutdownNow();
        sessions.clear();
    }
}
