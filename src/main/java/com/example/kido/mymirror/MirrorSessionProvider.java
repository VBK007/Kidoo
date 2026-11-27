package com.example.kido.mymirror;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import com.example.kido.common.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import com.example.kido.mymirror.repo.AdGateRepository;
import com.example.kido.mymirror.service.DispatcherService;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/** Holds the operator-provisioned upstream session and keeps it warm through the managed client. */
@Slf4j
@Component
public class MirrorSessionProvider {

    private final MirrorSessionProperties session;
    private final AdGateRepository adGate;
    private final DispatcherService dispatcher;

    @org.springframework.beans.factory.annotation.Autowired
    private MirrorBulkhead bulkhead;

    @org.springframework.beans.factory.annotation.Autowired
    private SessionRecoveryProperties recovery;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<SessionRecoveryCoordinator> coordinator;

    public enum State { UNKNOWN, REACHABLE, VERIFIED, VALID, MISSING, REVOKED, ACCESS_DENIED, THROTTLED, TEMPORARILY_UNAVAILABLE }

    private record Session(String cookie, long generation, State state, String catalogueEpoch) {
        Session withState(State next) {
            return new Session(cookie, generation, next, catalogueEpoch);
        }
    }
    private volatile Session current = new Session(null, 0, State.MISSING, null);
    private final java.util.Set<String> blocked = new java.util.HashSet<>();
    private final java.util.Map<String, State> callerDenials = new java.util.HashMap<>();
    private static final int MAX_DENIALS = 4096;

    public MirrorSessionProvider(MirrorSessionProperties session, AdGateRepository adGate,
                                 DispatcherService dispatcher) {
        this.session = session;
        this.adGate = adGate;
        this.dispatcher = dispatcher;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void registerMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
        for (State state : State.values()) {
            registry.gauge("mirror.session.state", io.micrometer.core.instrument.Tags.of("state", state.name()),
                    this, provider -> provider.state() == state ? 1d : 0d);
        }
    }

    @Scheduled(initialDelayString = "${app.mymirror.session.reload-interval:30s}",
               fixedDelayString = "${app.mymirror.session.reload-interval:30s}")
    void reloadOperatorFile() {
        if (recovery == null || !recovery.isEnabled()) load();
    }

    @PostConstruct
    synchronized void load() {
        Path file = sessionFile();
        try {
            if (Files.notExists(file)) {
                removeSession();
                return;
            }
            String before = fileEpoch(file);
            String saved = (recovery != null && recovery.isEnabled()
                    ? RecoveryFiles.read(file) : Files.readString(file, StandardCharsets.UTF_8)).trim();
            boolean valid = saved.indexOf('\r') < 0 && saved.indexOf('\n') < 0
                    && Arrays.stream(saved.split(";")).map(String::trim)
                        .anyMatch(part -> part.startsWith("t_hash_t=") && part.length() > "t_hash_t=".length());
            if (!valid) {
                removeSession();
                return;
            }
            String after = fileEpoch(file);
            boolean sameCredential = saved.equals(current.cookie());
            boolean stableEpoch = before != null && before.equals(after);
            if ((!sameCredential || stableEpoch && !before.equals(current.catalogueEpoch()))
                    && !blocked.contains(identity(saved)) && (!sameCredential || current.state() != State.REVOKED
                    && current.state() != State.ACCESS_DENIED)) {
                if (!sameCredential && current.cookie() != null) blocked.add(identity(current.cookie()));
                callerDenials.remove(identity(saved));
                String epoch = stableEpoch ? before : UUID.randomUUID().toString();
                current = new Session(saved, current.generation() + 1, State.UNKNOWN, epoch);
                log.info("Loaded operator-provisioned mirror session");
            }
        } catch (IOException ex) {
            log.warn("Mirror session file could not be read: {}", ex.getClass().getSimpleName());
        }
    }

    private void removeSession() {
        if (current.cookie() != null) {
            blocked.add(identity(current.cookie()));
            current = new Session(null, current.generation() + 1, State.MISSING, null);
        }
    }

    public State state() {
        return current.state();
    }

    public String currentCookie() {
        Session held = current;
        return held.state() == State.REVOKED ? null : held.cookie();
    }

    public String ensureSession() {
        Session held = current;
        if (held.state() == State.REVOKED) throw new Revoked();
        return held.cookie();
    }

    public synchronized long generation(String cookie) {
        State denied = callerDenials.get(identity(cookie));
        if (denied == State.ACCESS_DENIED) throw new MirrorRequestLimit.AccessDenied();
        if (denied == State.REVOKED || blocked.contains(identity(cookie))) throw new Revoked();
        if (matches(cookie)) {
            if (current.state() == State.REVOKED) throw new Revoked();
            if (current.state() == State.ACCESS_DENIED) throw new MirrorRequestLimit.AccessDenied();
            return current.generation();
        }
        // When the terminal-state registry is full, do not allow an untracked caller credential.
        if (callerDenials.size() >= MAX_DENIALS) throw new MirrorRequestLimit.AccessDenied();
        return 0;
    }

    public synchronized void ifCurrent(String cookie, long generation, Runnable publish) {
        if (generation(cookie) != generation) throw new Revoked();
        publish.run();
    }

    public synchronized void noteRejected(String cookie) {
        observe(cookie, State.REVOKED);
    }

    public synchronized void observe(String cookie, State next) {
        if (cookie == null) return;
        if (!matches(cookie)) {
            if ((next == State.REVOKED || next == State.ACCESS_DENIED) && callerDenials.size() < MAX_DENIALS) {
                callerDenials.put(identity(cookie), next);
            }
            return;
        }
        if (current.state() == State.REVOKED || current.state() == State.ACCESS_DENIED) return;
        if (next == State.REACHABLE && (current.state() == State.VERIFIED || current.state() == State.VALID)
                || next == State.VERIFIED && current.state() == State.VALID) return;
        current = current.withState(next);
    }

    boolean recoveryEnabled() {
        return recovery != null && recovery.isEnabled();
    }

    boolean confirmedExpiry(java.net.URI endpoint, int status, org.springframework.http.HttpHeaders headers,
                            String cookie, long generation) {
        if (!recoveryEnabled() || !endpoint.equals(recovery.getExpiryEndpoint())
                || status != recovery.getExpiryStatus()
                || headers.containsHeader("cf-mitigated")
                || !recovery.getExpiryValue().equals(headers.getFirst(recovery.getExpiryHeader()))) return false;
        if (generation == 0) noteRejected(cookie);
        else confirmCurrentExpiry(cookie, generation);
        return true;
    }

    private synchronized void confirmCurrentExpiry(String cookie, long generation) {
        if (generation != 0 && current.generation() == generation && matches(cookie)
                && current.state() != State.REVOKED) {
            current = current.withState(State.REVOKED);
            coordinator.getObject().begin(generation, identity(current.cookie()));
        }
    }

    synchronized void restoreRevocation(String fingerprint) {
        if (current.cookie() != null && identity(current.cookie()).equals(fingerprint)) {
            current = current.withState(State.REVOKED);
        } else {
            blocked.add(fingerprint);
        }
    }

    synchronized void blockUnvalidatedFile() {
        if (current.cookie() != null) {
            current = current.withState(State.REVOKED);
        }
    }

    synchronized long activeGeneration() {
        return current.generation();
    }

    synchronized void activate(long generation, String expected, String candidate) throws IOException {
        if (current.generation() != generation || current.state() != State.REVOKED
                || !identity(current.cookie()).equals(expected)
                || !RecoveryFiles.read(sessionFile()).trim().equals(current.cookie())) {
            throw new IOException("Active recovery generation changed");
        }
        try {
            RecoveryFiles.replace(sessionFile(), candidate + "\n");
        } catch (IOException ex) {
            // Directory fsync can fail after the atomic rename. Keep the validated file and
            // durable activation intent available for restart reconciliation in that case.
            if (RecoveryFiles.read(sessionFile()).trim().equals(candidate)) {
                publishActivation(generation, expected, candidate);
            }
            throw ex;
        }
        publishActivation(generation, expected, candidate);
    }

    private void publishActivation(long generation, String expected, String candidate) {
        blocked.add(expected);
        callerDenials.remove(identity(candidate));
        String epoch = fileEpoch(sessionFile());
        current = new Session(candidate, generation + 1, State.VERIFIED,
                epoch == null ? UUID.randomUUID().toString() : epoch);
    }

    /**
     * Only the operator credential has a durable catalogue namespace. A caller-supplied
     * credential outside that boundary may use the ordinary per-session memory cache.
     */
    public synchronized String catalogueEpoch(String cookie, long generation) {
        if (generation(cookie) != generation) throw new Revoked();
        if (generation == 0) return null;
        return current.catalogueEpoch() + "\n" + identity(current.cookie());
    }

    private static String fileEpoch(Path file) {
        try {
            var attributes = Files.readAttributes(file, "unix:dev,ino,ctime");
            if (attributes.get("dev") == null || attributes.get("ino") == null || attributes.get("ctime") == null) {
                return null;
            }
            return attributes.get("dev") + ":" + attributes.get("ino") + ":" + attributes.get("ctime");
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException ex) {
            // A deployment without stable file metadata gets a cold namespace on restart.
            return null;
        }
    }

    private boolean matches(String cookie) {
        return current.cookie() != null && cookie != null && identity(current.cookie()).equals(identity(cookie));
    }

    static String identity(String cookie) {
        if (cookie == null) return "";
        String normalized = Arrays.stream(cookie.split(";")).map(String::trim)
                .filter(part -> !part.isEmpty() && !part.startsWith("ott="))
                .collect(java.util.stream.Collectors.joining(";"));
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static final class Revoked extends ApiException {
        public Revoked() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "The upstream session was revoked and requires replacement");
        }
    }

    @Scheduled(initialDelayString = "${app.mymirror.session.keep-alive-interval:2m}",
               fixedDelayString = "${app.mymirror.session.keep-alive-interval:2m}")
    void scheduledKeepSessionWarm() {
        if (session.isKeepAliveEnabled() && currentCookie() != null) bulkhead.maintenance(this::keepSessionWarm);
    }

    void keepSessionWarm() {
        String held = currentCookie();
        if (!session.isKeepAliveEnabled() || held == null || state() == State.ACCESS_DENIED) return;
        try {
            // An uncleared response alone does not establish that the session was revoked.
            adGate.verifyAds(dispatcher.resolveActiveBaseUrl(), held);
        } catch (MirrorRequestLimit.AccessDenied ex) {
            observe(held, State.ACCESS_DENIED);
        } catch (HttpStatusCodeException ex) {
            if (MirrorCloudflare.isChallenge(ex) || ex.getStatusCode().value() == 403) {
                observe(held, State.ACCESS_DENIED);
            } else {
                log.debug("Mirror session keep-alive returned status {}", ex.getStatusCode().value());
            }
        } catch (MirrorRequestLimit.Throttled ex) {
            observe(held, State.THROTTLED);
        } catch (MirrorRequestLimit.Busy ex) {
            log.debug("Mirror session keep-alive deferred");
        } catch (RuntimeException ex) {
            log.debug("Mirror session keep-alive failed: {}", ex.getClass().getSimpleName());
        }
    }

    public String unavailableReason() {
        return "No mirror session is available; an operator must provide an authorized upstream session, then retry";
    }

    Path sessionFile() {
        String configured = session.getSessionFile();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(session.getToolDir(), "cdp-capture", "session.txt").toAbsolutePath().normalize();
    }
}
