package com.example.kido.mymirror;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SessionRecoveryCoordinator {
    private static final Logger log = LoggerFactory.getLogger(SessionRecoveryCoordinator.class);

    public enum State { IDLE, AWAITING_OPERATOR, ACQUIRING, VALIDATING, ACTIVATING, SUCCEEDED, FAILED, TIMED_OUT }

    public record Status(UUID incidentId, UUID attemptId, State state, Long generation, Instant startedAt,
                         Instant updatedAt, Instant lastSuccessAt, Integer processExit, String outcome) {}

    private record Journal(Status status, String revokedFingerprint, String candidateFingerprint) {}

    private final SessionRecoveryProperties config;
    private final MirrorAccessProperties access;
    private final MirrorSessionProvider sessions;
    private final RestTemplate validator;
    private final MeterRegistry metrics;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("mirror-session-recovery").factory());

    private volatile Journal journal = new Journal(new Status(null, null, State.IDLE, null, null,
            null, null, null, null), null, null);
    // Set before submission and cleared only after the worker has finished. At most one task is admitted.
    private boolean running;
    private boolean closing;
    private record Pending(long generation, String fingerprint) {}
    private Pending pending;
    private volatile Process acquisition;
    private volatile UUID cancelledAttempt;
    private FileChannel lockChannel;
    private FileLock lock;
    private Path staging;
    private Path metadata;

    public SessionRecoveryCoordinator(SessionRecoveryProperties config, MirrorAccessProperties access,
            MirrorSessionProvider sessions, @Qualifier("mirrorRecoveryValidationClient") RestTemplate validator,
            MeterRegistry metrics) {
        this.config = config;
        this.access = access;
        this.sessions = sessions;
        this.validator = validator;
        this.metrics = metrics;
    }

    @PostConstruct
    void initialize() throws IOException {
        for (State state : State.values()) {
            metrics.gauge("mirror.recovery.state", io.micrometer.core.instrument.Tags.of("state", state.name()),
                    this, coordinator -> coordinator.status().state() == state ? 1 : 0);
        }
        metrics.gauge("mirror.recovery.last.success.epoch.seconds", this, coordinator -> {
            Instant success = coordinator.status().lastSuccessAt();
            return success == null ? 0d : success.getEpochSecond();
        });
        if (!config.isEnabled()) return;

        int signal = config.getExpiryStatus();
        if (signal < 400 || signal >= 500 || signal == 403 || signal == 412 || signal == 429
                || config.getExpiryHeader().isBlank() || config.getExpiryValue().isBlank()
                || config.getValidationHeader().isBlank() || config.getValidationValue().isBlank()
                || config.getStagingDirectory() == null || config.getMetadataPath() == null
                || config.getAcquisitionTimeout().toMillis() <= 0 || config.getValidationTimeout().toMillis() <= 0
                || config.getFailureCooldown().isNegative()) {
            throw new IllegalStateException("Authorized recovery configuration is incomplete");
        }
        checkEndpoint(config.getExpiryEndpoint());
        checkEndpoint(config.getValidationEndpoint());

        staging = config.getStagingDirectory().toAbsolutePath().normalize();
        metadata = config.getMetadataPath().toAbsolutePath().normalize();
        RecoveryFiles.requirePrivate(staging, true);
        RecoveryFiles.requirePrivate(staging.resolve("operator"), true);
        RecoveryFiles.requirePrivate(metadata.getParent(), true);
        RecoveryFiles.requirePrivate(sessions.sessionFile().getParent(), true);
        if (Files.exists(sessions.sessionFile(), NOFOLLOW_LINKS)) {
            RecoveryFiles.requirePrivate(sessions.sessionFile(), false);
        }
        if (!Files.getFileStore(staging).equals(Files.getFileStore(sessions.sessionFile().getParent()))) {
            throw new IOException("Recovery staging must be on the session filesystem");
        }
        if (metadata.equals(sessions.sessionFile())) throw new IOException("Recovery metadata and session paths must differ");

        Path lockPath = metadata.resolveSibling(metadata.getFileName() + ".lock");
        if (lockPath.equals(sessions.sessionFile())) throw new IOException("Recovery lock and session paths must differ");
        try {
            Files.createFile(lockPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Another instance may own the lock. Taking it below is the authority.
        }
        RecoveryFiles.requirePrivate(lockPath, false);
        lockChannel = FileChannel.open(lockPath, java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("Another process owns mirror recovery");
            reconcile();
        } catch (IOException | RuntimeException ex) {
            lockChannel.close();
            throw ex;
        }
    }

    private void reconcile() throws IOException {
        if (Files.notExists(metadata, NOFOLLOW_LINKS)) return;
        journal = mapper.readValue(RecoveryFiles.read(metadata), Journal.class);
        if (journal.revokedFingerprint() == null) return;
        String active = MirrorSessionProvider.identity(sessions.currentCookie());
        sessions.restoreRevocation(journal.revokedFingerprint());
        boolean committed = active.equals(journal.candidateFingerprint());
        boolean old = active.equals(journal.revokedFingerprint());
        if (!committed && !old) sessions.blockUnvalidatedFile();
        if (status().state() == State.ACTIVATING && committed) {
            transition(State.SUCCEEDED, status().processExit(), "ACTIVATION_RECONCILED", journal.candidateFingerprint());
        } else if (status().state() != State.SUCCEEDED && status().state() != State.FAILED
                && status().state() != State.TIMED_OUT) {
            transition(State.FAILED, status().processExit(), "INTERRUPTED_BY_RESTART", null);
        }
        if (status().attemptId() != null) cleanup(staging.resolve(status().attemptId() + ".cookie"));
    }

    private void checkEndpoint(URI uri) {
        if (uri == null || !access.isApproved(uri) || uri.getRawQuery() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                     || "http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()))) {
            throw new IllegalStateException("Recovery requires an exact approved credential endpoint");
        }
    }

    public Status status() {
        return journal.status();
    }

    void begin(long generation, String fingerprint) {
        synchronized (this) {
            if (!config.isEnabled() || closing || fingerprint.equals(journal.revokedFingerprint())) return;
            if (running) {
                pending = new Pending(generation, fingerprint);
                return;
            }
            Status previous = status();
            Instant now = Instant.now();
            journal = new Journal(new Status(UUID.randomUUID(), UUID.randomUUID(), State.AWAITING_OPERATOR,
                    generation, now, now, previous.lastSuccessAt(), null, null), fingerprint, null);
            launch();
        }
    }

    public boolean retry() {
        // Avoid taking the coordinator lock before the provider lock used by the trigger path.
        long generation = sessions.activeGeneration();
        if (sessions.state() != MirrorSessionProvider.State.REVOKED) return false;
        synchronized (this) {
            Status previous = status();
            if (!config.isEnabled() || closing || running || (previous.state() != State.FAILED && previous.state() != State.TIMED_OUT)
                    || Instant.now().isBefore(previous.updatedAt().plus(config.getFailureCooldown()))) return false;
            Instant now = Instant.now();
            journal = new Journal(new Status(previous.incidentId(), UUID.randomUUID(), State.AWAITING_OPERATOR,
                    generation, previous.startedAt(), now, previous.lastSuccessAt(), null, null),
                    journal.revokedFingerprint(), null);
            launch();
            return true;
        }
    }

    public synchronized boolean cancelAcquisition() {
        Process process = acquisition;
        if (!running || status().state() != State.ACQUIRING || process == null || !process.isAlive()) return false;
        cancelledAttempt = status().attemptId();
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        return true;
    }

    private void launch() {
        running = true;
        worker.execute(this::recover);
    }

    private void recover() {
        Status incident = status();
        Path candidate = staging.resolve(incident.attemptId() + ".cookie");
        Process process = null;
        Integer exit = null;
        long acquiredAt = System.nanoTime();
        boolean acquisitionMeasured = false;
        String step = "ACQUISITION";
        try {
            transition(State.AWAITING_OPERATOR, null, "OPERATOR_HANDOFF_REQUIRED", null);
            var builder = new ProcessBuilder(config.getNode(), config.getScript().toAbsolutePath().normalize().toString(),
                    candidate.toString(), Long.toString(config.getAcquisitionTimeout().toMillis()));
            builder.directory(config.getWorkingDirectory().toAbsolutePath().normalize().toFile());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            process = builder.start();
            acquisition = process;
            process.getOutputStream().close();
            transition(State.ACQUIRING, null, "PROCESS_STARTED", null);
            if (!process.waitFor(config.getAcquisitionTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                transition(State.TIMED_OUT, null, "ACQUISITION_TIMEOUT", null);
                return;
            }
            exit = process.exitValue();
            if (incident.attemptId().equals(cancelledAttempt)) {
                transition(State.FAILED, exit, "CANCELLED", null);
                return;
            }
            if (exit != 0) {
                transition(exit == 3 ? State.TIMED_OUT : State.FAILED, exit, "ACQUISITION_EXIT", null);
                return;
            }
            metrics.timer("mirror.recovery.acquisition").record(System.nanoTime() - acquiredAt, TimeUnit.NANOSECONDS);
            acquisitionMeasured = true;

            step = "VALIDATION";
            transition(State.VALIDATING, exit, null, null);
            String credential = readCandidate(candidate);
            String fingerprint = MirrorSessionProvider.identity(credential);
            if (fingerprint.equals(journal.revokedFingerprint())) throw new IOException("Replacement matches revoked credential");
            validate(credential);
            metrics.counter("mirror.recovery.validation", "outcome", "accepted").increment();

            step = "ACTIVATION";
            transition(State.ACTIVATING, exit, null, fingerprint);
            sessions.activate(incident.generation(), journal.revokedFingerprint(), credential);
            transition(State.SUCCEEDED, exit, "SESSION_ACTIVATED", fingerprint);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            recordFailure(exit, "CANCELLED");
        } catch (IOException | RuntimeException ex) {
            if ("VALIDATION".equals(step)) metrics.counter("mirror.recovery.validation", "outcome", "failed").increment();
            if ("ACTIVATION".equals(step)
                    && MirrorSessionProvider.identity(sessions.currentCookie()).equals(journal.candidateFingerprint())) {
                // Activation already committed. Preserve the durable ACTIVATING record for reconciliation.
                log.warn("Mirror recovery activated but completion metadata could not be persisted");
            } else {
                if ("VALIDATION".equals(step) && isTimeout(ex)) {
                    recordOutcome(State.TIMED_OUT, exit, "VALIDATION_TIMEOUT");
                } else {
                    recordFailure(exit, step + "_FAILED");
                }
            }
        } finally {
            if (!acquisitionMeasured) {
                metrics.timer("mirror.recovery.acquisition").record(System.nanoTime() - acquiredAt, TimeUnit.NANOSECONDS);
            }
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.onExit().join();
            }
            acquisition = null;
            cleanup(candidate);
            synchronized (this) {
                running = false;
                if (pending != null) {
                    Pending next = pending;
                    pending = null;
                    begin(next.generation(), next.fingerprint());
                }
            }
        }
    }

    private void cleanup(Path candidate) {
        try {
            Files.deleteIfExists(candidate);
            Files.deleteIfExists(staging.resolve("operator").resolve(candidate.getFileName()));
        } catch (IOException ex) {
            log.warn("Mirror recovery staging cleanup failed");
        }
    }

    private static String readCandidate(Path path) throws IOException {
        String value = RecoveryFiles.read(path).strip();
        if (value.isBlank() || value.chars().anyMatch(c -> c < 0x20 || c > 0x7e)
                || java.util.Arrays.stream(value.split(";")).map(String::trim)
                    .filter(pair -> pair.startsWith("t_hash_t=") && pair.length() > "t_hash_t=".length()).count() != 1) {
            throw new IOException("Invalid staged credential");
        }
        return value;
    }

    private void validate(String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, credential);
        var result = MirrorRequestContext.within(config.getValidationTimeout(),
                () -> validator.exchange(config.getValidationEndpoint(), HttpMethod.GET,
                        new HttpEntity<>(headers), Void.class));
        if (!result.getStatusCode().is2xxSuccessful()
                || result.getHeaders().containsHeader("cf-mitigated")
                || !config.getValidationValue().equals(result.getHeaders().getFirst(config.getValidationHeader()))) {
            throw new IllegalStateException("Provider did not validate the replacement");
        }
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MirrorRequestLimit.DeadlineExceeded || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException) return true;
        }
        return false;
    }

    private void recordFailure(Integer exit, String reason) {
        recordOutcome(State.FAILED, exit, reason);
    }

    private void recordOutcome(State state, Integer exit, String reason) {
        try {
            transition(state, exit, reason, null);
        } catch (IOException ex) {
            Status previous = status();
            journal = new Journal(new Status(previous.incidentId(), previous.attemptId(), State.FAILED,
                    previous.generation(), previous.startedAt(), Instant.now(), previous.lastSuccessAt(),
                    exit, "JOURNAL_WRITE_FAILED"), journal.revokedFingerprint(), null);
            log.warn("Mirror recovery failure metadata could not be persisted");
        }
    }

    private void transition(State next, Integer exit, String outcome, String candidateFingerprint) throws IOException {
        Status previous = status();
        Instant now = Instant.now();
        Status updated = new Status(previous.incidentId(), previous.attemptId(), next, previous.generation(),
                previous.startedAt(), now, next == State.SUCCEEDED ? now : previous.lastSuccessAt(), exit, outcome);
        Journal data = new Journal(updated, journal.revokedFingerprint(), candidateFingerprint);
        RecoveryFiles.replace(metadata, mapper.writeValueAsString(data));
        journal = data;
        metrics.counter("mirror.recovery.transitions", "state", next.name()).increment();
        log.atInfo().addKeyValue("incidentId", updated.incidentId()).addKeyValue("state", next)
                .addKeyValue("generation", updated.generation()).addKeyValue("processExit", exit)
                .addKeyValue("outcome", outcome).log("Mirror recovery transition");
    }

    @PreDestroy
    void stop() throws IOException, InterruptedException {
        synchronized (this) {
            closing = true;
            worker.shutdownNow();
        }
        Process process = acquisition;
        if (process != null) process.destroyForcibly();
        if (worker.awaitTermination(5, TimeUnit.SECONDS)) {
            if (lock != null) lock.close();
            if (lockChannel != null) lockChannel.close();
        }
    }
}
