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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

    @Test
    @EnabledIfEnvironmentVariable(named = "KIDOO_TEST_WORKER_SOCKET", matches = "/.+")
    void dockerWorkerAcquisitionAndCoordinatorReconcileTheSameIncident() throws Exception {
        Path adapter = Path.of("tools/netmirror-capture/acquire-worker.mjs").toAbsolutePath();
        String socket = System.getenv("KIDOO_TEST_WORKER_SOCKET");
        String worker = System.getenv("KIDOO_TEST_WORKER_CONTAINER");
        assertTrue(worker != null && worker.matches("kidoo-integrated-worker-[0-9]+"), "Expected a disposable worker");
        var receivedCandidate = new CountDownLatch(1);
        var allowValidation = new CountDownLatch(1);
        try (var fixture = new Fixture("exit 5\n", true, Duration.ofSeconds(150))) {
            var script = Files.writeString(root.resolve("worker-adapter.mjs"), """
                    import { appendFile } from 'node:fs/promises';
                    import { acquire } from '%s';
                    await appendFile('launches', 'launch\\n');
                    process.exitCode = await acquire(process.argv[2], Number(process.argv[3]), '%s');
                    """.formatted(adapter.toUri(), socket));
            fixture.config.setScript(script);
            fixture.config.setNode(System.getenv("KIDOO_TEST_WORKER_NODE"));
            fixture.config.setValidationTimeout(Duration.ofSeconds(30));
            fixture.beforeValidationResponse = () -> {
                receivedCandidate.countDown();
                try {
                    if (!allowValidation.await(15, TimeUnit.SECONDS)) throw new java.io.IOException("Fixture validation blocked");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(ex);
                }
            };
            fixture.mock.createContext("/expiry", exchange -> {
                try (exchange) {
                    assertEquals(OLD, exchange.getRequestHeaders().getFirst("Cookie"));
                    exchange.getResponseHeaders().set("X-Fixture-Session", "expired");
                    exchange.sendResponseHeaders(401, -1);
                }
            });
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("sessions", fixture.provider);
            var upstream = new MirrorHttpConfig().restTemplate(fixture.access, new MirrorRequestLimit(),
                    beans.getBeanProvider(MirrorSessionProvider.class));
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.COOKIE, OLD);
            assertThrows(MirrorSessionProvider.Revoked.class, () -> upstream.exchange(
                    fixture.config.getExpiryEndpoint(), org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers), Void.class));
            var start = new CountDownLatch(1);
            long oldGeneration = fixture.generation;
            try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
                var calls = java.util.stream.IntStream.range(0, 199).mapToObj(i -> clients.submit(() -> {
                    start.await();
                    fixture.confirm();
                    return null;
                })).toList();
                start.countDown();
                for (var call : calls) call.get(10, TimeUnit.SECONDS);
            }
            assertTrue(receivedCandidate.await(165, TimeUnit.SECONDS), () -> fixture.coordinator.status().toString());
            try {
                assertEquals(SessionRecoveryCoordinator.State.VALIDATING, fixture.coordinator.status().state());
                assertEquals(OLD, Files.readString(fixture.active).strip());
                try (var files = Files.list(fixture.config.getStagingDirectory())) {
                    Path staged = files.filter(path -> path.toString().endsWith(".cookie")).findFirst().orElseThrow();
                    assertEquals(NEW, Files.readString(staged).strip());
                    assertEquals(fromString("rw-------"), Files.getPosixFilePermissions(staged));
                }
                var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                        .standaloneSetup(new com.example.kido.common.HealthController()).build();
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/health"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            } finally {
                allowValidation.countDown();
            }
            fixture.await(SessionRecoveryCoordinator.State.SUCCEEDED, Duration.ofSeconds(25));
            assertEquals(1, fixture.validations.get());
            assertEquals(NEW + "\n", Files.readString(fixture.active));
            assertEquals(oldGeneration + 1, fixture.provider.generation(NEW));
            assertEquals(List.of("launch"), Files.readAllLines(root.resolve("launches")));
            dockerFixture("exec", worker, "sh", "-c", "test -z \"$(find /tmp -maxdepth 1 -name 'kidoo-browser-*')\"");
            var incident = fixture.coordinator.status().incidentId();
            String journal = RecoveryFiles.read(fixture.config.getMetadataPath());
            fixture.coordinator.stop();
            RecoveryFiles.replace(fixture.config.getMetadataPath(),
                    journal.replace("\"state\":\"SUCCEEDED\"", "\"state\":\"ACTIVATING\""));
            dockerFixture("restart", worker);
            fixture.reopen();
            assertEquals(incident, fixture.coordinator.status().incidentId());
            assertEquals("ACTIVATION_RECONCILED", fixture.coordinator.status().outcome());
            assertEquals(NEW, fixture.provider.ensureSession());
            assertEquals(List.of("launch"), Files.readAllLines(root.resolve("launches")));
        }
    }

    private static void dockerFixture(String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("docker");
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Docker fixture command timed out");
            assertEquals(0, process.exitValue());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
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
            await(expected, Duration.ofSeconds(7));
        }

        void await(SessionRecoveryCoordinator.State expected, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
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
