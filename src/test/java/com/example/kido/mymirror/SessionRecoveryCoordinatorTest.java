package com.example.kido.mymirror;

import static java.nio.file.attribute.PosixFilePermissions.fromString;
import static java.nio.file.attribute.PosixFilePermissions.asFileAttribute;
import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SessionRecoveryCoordinatorTest {
    @TempDir Path root;
    private static final String OLD = "t_hash_t=fixture-old";
    private static final String NEW = "t_hash_t=fixture-new";
    private static final String HANDOFF = """
            printf 'launch\\n' >> launches
            umask 077
            printf 't_hash_t=fixture-new\\n' > "$1"
            """;

    @Test
    void twoHundredRequestsOpenOneIncidentAndActivateWithoutRevivingOldGeneration() throws Exception {
        try (var fixture = new Fixture(HANDOFF, true, Duration.ofSeconds(5))) {
            long old = fixture.generation;
            var start = new CountDownLatch(1);
            try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
                var pending = java.util.stream.IntStream.range(0, 200)
                        .mapToObj(i -> clients.submit(() -> {
                            start.await();
                            fixture.confirm();
                            return null;
                        })).toList();
                start.countDown();
                for (var request : pending) request.get(5, TimeUnit.SECONDS);
            }
            fixture.await(SessionRecoveryCoordinator.State.SUCCEEDED);
            assertEquals(List.of("launch"), Files.readAllLines(root.resolve("launches")));
            assertEquals(1, fixture.validations.get());
            assertEquals(NEW, fixture.provider.ensureSession());
            assertEquals(old + 1, fixture.provider.generation(NEW));
            assertThrows(MirrorSessionProvider.Revoked.class,
                    () -> fixture.provider.ifCurrent(OLD, old, () -> fail("stale cache publication")));
            fixture.confirm();
            assertEquals(1, Files.readAllLines(root.resolve("launches")).size());
        }
    }

    @Test
    void failedAndInvalidCandidatesLeaveTheActiveFileUnchanged() throws Exception {
        try (var fixture = new Fixture("exit 5\n", true, Duration.ofSeconds(5))) {
            fixture.confirm();
            fixture.await(SessionRecoveryCoordinator.State.FAILED);
            assertEquals(5, fixture.coordinator.status().processExit());
            assertEquals(OLD, Files.readString(fixture.active).strip());
            assertEquals(0, fixture.validations.get());
            Files.writeString(fixture.config.getScript(), HANDOFF);
            fixture.allowCandidate = false;
            fixture.awaitIdleRetry();
            fixture.await(SessionRecoveryCoordinator.State.FAILED);
            assertEquals(OLD, Files.readString(fixture.active).strip());
            assertEquals(1, fixture.validations.get());
        }
    }

    @Test
    void timesOutAndCancelsAcquisition() throws Exception {
        try (var fixture = new Fixture("exec /bin/sleep 15\n", true, Duration.ofMillis(150))) {
            fixture.confirm();
            fixture.await(SessionRecoveryCoordinator.State.TIMED_OUT);
            assertEquals(OLD, Files.readString(fixture.active).strip());
            assertEquals(0, fixture.validations.get());
        }
    }

    @Test
    void doesNotOverwriteAConcurrentFileReplacement() throws Exception {
        try (var fixture = new Fixture(HANDOFF, true, Duration.ofSeconds(5))) {
            fixture.beforeValidationResponse = () -> RecoveryFiles.replace(fixture.active, "t_hash_t=other\n");
            fixture.confirm();
            fixture.await(SessionRecoveryCoordinator.State.FAILED);
            assertEquals("t_hash_t=other", Files.readString(fixture.active).strip());
            assertEquals(MirrorSessionProvider.State.REVOKED, fixture.provider.state());
        }
    }

    @Test
    void restartReconcilesInterruptedIncidentWithoutStartingAnotherProcess() throws Exception {
        try (var fixture = new Fixture("printf 'launch\\n' >> launches\nexec /bin/sleep 30\n",
                true, Duration.ofSeconds(20))) {
            fixture.confirm();
            fixture.await(SessionRecoveryCoordinator.State.ACQUIRING);
            var id = fixture.coordinator.status().incidentId();
            // Snapshot durable acquisition state, simulating a crash instead of graceful cancellation.
            String journal = RecoveryFiles.read(fixture.config.getMetadataPath());
            fixture.coordinator.stop();
            RecoveryFiles.replace(fixture.config.getMetadataPath(), journal);
            fixture.reopen();
            assertEquals(id, fixture.coordinator.status().incidentId());
            assertEquals(SessionRecoveryCoordinator.State.FAILED, fixture.coordinator.status().state());
            assertEquals("INTERRUPTED_BY_RESTART", fixture.coordinator.status().outcome());
            assertThrows(MirrorSessionProvider.Revoked.class, fixture.provider::ensureSession);
            fixture.confirm();
            assertEquals(id, fixture.coordinator.status().incidentId());
            assertTrue(Files.readAllLines(root.resolve("launches")).size() <= 1);
        }
    }

    @Test
    void restartRecognizesTheValidatedFileWhenInterruptedAfterRename() throws Exception {
        try (var fixture = new Fixture(HANDOFF, true, Duration.ofSeconds(5))) {
            fixture.confirm();
            fixture.await(SessionRecoveryCoordinator.State.SUCCEEDED);
            var id = fixture.coordinator.status().incidentId();
            String journal = RecoveryFiles.read(fixture.config.getMetadataPath());
            fixture.coordinator.stop();
            RecoveryFiles.replace(fixture.config.getMetadataPath(),
                    journal.replace("\"state\":\"SUCCEEDED\"", "\"state\":\"ACTIVATING\""));
            fixture.reopen();
            assertEquals(id, fixture.coordinator.status().incidentId());
            assertEquals("ACTIVATION_RECONCILED", fixture.coordinator.status().outcome());
            assertEquals(NEW, fixture.provider.ensureSession());
            assertEquals(1, Files.readAllLines(root.resolve("launches")).size());
        }
    }

    @Test
    void throttleForbiddenAndUnqualifiedResponsesDoNotStartAnIncident() throws Exception {
        try (var fixture = new Fixture(HANDOFF, true, Duration.ofSeconds(5))) {
            var provider = fixture.provider;
            var headers = fixture.signal();
            var endpoint = fixture.config.getExpiryEndpoint();
            for (int status : List.of(429, 403, 503, 200)) {
                assertFalse(provider.confirmedExpiry(endpoint, status, headers, OLD, fixture.generation));
            }
            assertFalse(provider.confirmedExpiry(endpoint, 401, new HttpHeaders(), OLD, fixture.generation));
            headers.set("cf-mitigated", "challenge");
            assertFalse(provider.confirmedExpiry(endpoint, 401, headers, OLD, fixture.generation));
            assertEquals(SessionRecoveryCoordinator.State.IDLE, fixture.coordinator.status().state());
            assertFalse(Files.exists(root.resolve("launches")));
        }
    }

    @FunctionalInterface
    interface BeforeResponse { void run() throws java.io.IOException; }

    private final class Fixture implements AutoCloseable {
        final Path active;
        final HttpServer mock;
        final SessionRecoveryProperties config = new SessionRecoveryProperties();
        final MirrorSessionProperties file = new MirrorSessionProperties();
        final MirrorAccessProperties access = new MirrorAccessProperties();
        final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        final AtomicInteger validations = new AtomicInteger();
        volatile boolean allowCandidate;
        volatile BeforeResponse beforeValidationResponse = () -> {};
        MirrorSessionProvider provider;
        SessionRecoveryCoordinator coordinator;
        long generation;

        Fixture(String script, boolean valid, Duration timeout) throws Exception {
            allowCandidate = valid;
            Path privateRoot = Files.createDirectory(root.resolve("private"), asFileAttribute(fromString("rwx------")));
            Path staging = Files.createDirectory(privateRoot.resolve("staging"), asFileAttribute(fromString("rwx------")));
            Files.createDirectory(staging.resolve("operator"), asFileAttribute(fromString("rwx------")));
            active = privateRoot.resolve("session.txt");
            RecoveryFiles.replace(active, OLD + "\n");
            Path scriptFile = Files.writeString(root.resolve("mock.sh"), script);
            file.setSessionFile(active.toString());

            mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mock.createContext("/validate", exchange -> {
                try (exchange) {
                    validations.incrementAndGet();
                    beforeValidationResponse.run();
                    if (allowCandidate && NEW.equals(exchange.getRequestHeaders().getFirst("Cookie"))) {
                        exchange.getResponseHeaders().set("X-Fixture-Validated", "yes");
                    }
                    exchange.sendResponseHeaders(204, -1);
                }
            });
            mock.start();
            String origin = "http://127.0.0.1:" + mock.getAddress().getPort();
            access.setApprovedOrigins(List.of(origin));
            config.setEnabled(true);
            config.setNode("/bin/sh");
            config.setScript(scriptFile);
            config.setWorkingDirectory(root);
            config.setStagingDirectory(staging);
            config.setMetadataPath(privateRoot.resolve("recovery.json"));
            config.setAcquisitionTimeout(timeout);
            config.setFailureCooldown(Duration.ZERO);
            config.setExpiryEndpoint(URI.create(origin + "/expiry"));
            config.setExpiryStatus(401);
            config.setExpiryHeader("X-Fixture-Session");
            config.setExpiryValue("expired");
            config.setValidationEndpoint(URI.create(origin + "/validate"));
            config.setValidationHeader("X-Fixture-Validated");
            config.setValidationValue("yes");
            reopen();
        }

        void reopen() throws Exception {
            provider = new MirrorSessionProvider(file, null, null);
            ReflectionTestUtils.setField(provider, "recovery", config);
            coordinator = new SessionRecoveryCoordinator(config, access, provider,
                    new MirrorHttpConfig().mirrorRecoveryValidationClient(new MirrorRequestLimit()), metrics);
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("mirrorRecovery", coordinator);
            ReflectionTestUtils.setField(provider, "coordinator", beans.getBeanProvider(SessionRecoveryCoordinator.class));
            provider.load();
            generation = provider.generation(OLD);
            coordinator.initialize();
        }

        HttpHeaders signal() {
            var headers = new HttpHeaders();
            headers.set("X-Fixture-Session", "expired");
            return headers;
        }

        void confirm() {
            assertTrue(provider.confirmedExpiry(config.getExpiryEndpoint(), 401, signal(), OLD, generation));
        }

        void await(SessionRecoveryCoordinator.State expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
            while (coordinator.status().state() != expected && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(expected, coordinator.status().state(), () -> coordinator.status().outcome());
        }

        void awaitIdleRetry() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                if (coordinator.retry()) return;
                Thread.sleep(20);
            }
            fail("Recovery retry was not admitted");
        }

        @Override
        public void close() throws Exception {
            try {
                coordinator.stop();
            } finally {
                mock.stop(0);
                metrics.close();
            }
        }
    }
}
