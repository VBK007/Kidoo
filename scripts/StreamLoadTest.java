import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * How many people can actually watch the same film at once.
 *
 * Run it with no build step:
 *
 * <pre>
 *   java scripts/StreamLoadTest.java \
 *       --base http://localhost:8080 \
 *       --user someone --password secret \
 *       --item 881a9aa3-... \
 *       --viewers 100 --seconds 60
 * </pre>
 *
 * <h2>Run it on the server's own network</h2>
 *
 * Pointing this at a tunnel measures the tunnel. ngrok's free tier caps
 * concurrent connections and bandwidth well below what this asks for, so the
 * numbers would describe ngrok's throttle rather than this machine. Use
 * {@code http://localhost:8080} on the server itself, or the LAN address from
 * another box on the same switch.
 *
 * <h2>What it does</h2>
 *
 * Each simulated viewer asks for a playback decision exactly as the app does,
 * then pulls the returned stream with {@code Range} requests in 2 MB chunks.
 * Capabilities are declared generously on purpose, so the server chooses direct
 * play: a hundred transcodes would queue behind
 * {@code app.media.max-transcode-sessions} and the run would measure a queue
 * instead of the thing being tested.
 *
 * Two modes, and the difference matters:
 *
 * <ul>
 *   <li>{@code realtime} — each viewer pulls at roughly the file's own bitrate,
 *       which is what watching looks like. The number that matters is how many
 *       viewers kept up.</li>
 *   <li>{@code saturate} — everyone pulls as fast as they can. Answers "what is
 *       the ceiling", and will make the server look worse than any real evening
 *       ever would.</li>
 * </ul>
 *
 * <h2>Read the arithmetic before the results</h2>
 *
 * A hundred viewers of a 1.6 Mbps file is 160 Mbps of upstream. A domestic
 * connection does not have it, and neither does this one — {@code application
 * .properties} puts the household at about 40 Mbps up, which is four 1080p
 * streams or roughly twenty-five at 1.6 Mbps. Run over the LAN the wall moves
 * to the disk and the NIC and you will see a much larger number, but that number
 * is not how many friends can watch from outside the house.
 *
 * So this is not a pass/fail test. It answers: where does it bend, does it bend
 * gracefully, and what gives way first — threads, sockets, disk, or the pipe.
 */
public final class StreamLoadTest {

    // --- what the run was told --------------------------------------------

    private static String base = "http://localhost:8080";
    private static String user = null;
    private static String password = null;
    private static String token = null;
    private static String profileId = null;
    private static String itemId = null;
    private static int viewers = 100;
    private static int seconds = 60;
    private static String mode = "realtime";
    private static int rampSeconds = 10;
    private static long chunkBytes = 2L * 1024 * 1024;

    // --- what it measured --------------------------------------------------

    private static final LongAdder bytesRead = new LongAdder();
    private static final LongAdder requests = new LongAdder();
    private static final List<Long> latencies = java.util.Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, LongAdder> failures = new ConcurrentHashMap<>();
    private static final AtomicLong decisionsDirect = new AtomicLong();
    private static final AtomicLong decisionsTranscode = new AtomicLong();
    private static final LongAdder viewersKeptUp = new LongAdder();
    private static final LongAdder viewersFellBehind = new LongAdder();

    public static void main(String[] args) throws Exception {
        parse(args);
        if (itemId == null) {
            System.err.println("--item is required (a media item id; copy one from the app's URL or the catalog API)");
            System.exit(2);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newCachedThreadPool())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // Signing in and finding a profile are setup, not the test. A failure
        // here is almost always "the server is not where you said it was", and
        // a stack trace is a poor way to say that.
        try {
            if (token == null) {
                if (user == null || password == null) {
                    System.err.println("Give either --token, or --user and --password.");
                    System.exit(2);
                }
                token = login(client);
            }
            if (profileId == null) {
                profileId = firstProfile(client);
            }
        } catch (java.net.ConnectException e) {
            System.err.println("Nothing answered at " + base
                    + ". Is the server running, and is that the right address?");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Could not get started: " + e.getMessage());
            System.exit(1);
        }

        System.out.printf("%nTower stream load test%n");
        System.out.printf("  server    %s%n", base);
        System.out.printf("  item      %s%n", itemId);
        System.out.printf("  viewers   %d, ramped over %ds%n", viewers, rampSeconds);
        System.out.printf("  mode      %s, for %ds%n%n", mode, seconds);

        long startedAt = System.nanoTime();
        CountDownLatch done = new CountDownLatch(viewers);
        // One platform thread per viewer, each blocking on its own read. Not
        // virtual threads: the toolchain here builds on 21 but the `java` on the
        // PATH is 17, and a load test that will not start is worth nothing. A
        // hundred blocked threads costs a hundred stacks and no CPU.
        ExecutorService pool = Executors.newFixedThreadPool(viewers);
        long deadline = System.currentTimeMillis() + seconds * 1000L;

        for (int i = 0; i < viewers; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    // Staggered. A hundred simultaneous cold starts is a
                    // thundering herd nobody will ever produce, and it would
                    // hide the steady-state behaviour underneath it.
                    Thread.sleep((long) (rampSeconds * 1000L * ((double) index / Math.max(1, viewers))));
                    viewer(client, deadline);
                } catch (Exception e) {
                    count("viewer:" + e.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });
        }

        // Progress while it runs, so a run that is going badly can be stopped
        // without waiting for the summary.
        Thread reporter = startDaemon(() -> {
            long last = 0;
            try {
                while (done.getCount() > 0) {
                    Thread.sleep(5000);
                    long now = bytesRead.sum();
                    double mbps = (now - last) * 8.0 / 5.0 / 1_000_000.0;
                    last = now;
                    System.out.printf("  %5ds  %6.1f Mbps  %d requests  %d errors%n",
                            (System.nanoTime() - startedAt) / 1_000_000_000L,
                            mbps, requests.sum(), totalFailures());
                }
            } catch (InterruptedException ignored) {
                // Finished.
            }
        });

        done.await();
        reporter.interrupt();
        pool.shutdown();
        report((System.nanoTime() - startedAt) / 1_000_000.0);
    }

    private static Thread startDaemon(Runnable body) {
        Thread thread = new Thread(body, "load-reporter");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** One viewer: decide, then pull the stream until the clock runs out. */
    private static void viewer(HttpClient client, long deadline) throws Exception {
        String body = """
                {"deviceName":"loadtest","videoCodecs":["h264","hevc","vp9","av1"],\
                "audioCodecs":["aac","mp3","ac3","eac3","opus","vorbis","flac","dts"],\
                "containers":["mp4","matroska","webm","mkv"],"supportsHls":true}""";

        HttpResponse<String> decision = client.send(
                authed(HttpRequest.newBuilder(URI.create(base + "/api/media/items/" + itemId + "/playback-decision")))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (decision.statusCode() != 200) {
            count("decision:" + decision.statusCode());
            return;
        }
        String json = decision.body();
        String mode = field(json, "mode");
        if ("TRANSCODE".equals(mode)) {
            decisionsTranscode.incrementAndGet();
            // Pulling an HLS playlist properly is a different program. Counted
            // and skipped, so the summary says how many ended up here.
            return;
        }
        decisionsDirect.incrementAndGet();
        String url = base + field(json, "url");

        long offset = 0;
        long viewerBytes = 0;
        long viewerStart = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            long from = offset;
            long to = offset + chunkBytes - 1;

            long sentAt = System.nanoTime();
            HttpResponse<InputStream> response = client.send(
                    authed(HttpRequest.newBuilder(URI.create(url)))
                            .header("Range", "bytes=" + from + "-" + to)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            if (status != 206 && status != 200) {
                count("stream:" + status);
                return;
            }
            // Time to first byte, which is what a viewer feels as a stall. The
            // whole-chunk time is bandwidth and says something else.
            latencies.add((System.nanoTime() - sentAt) / 1_000_000);

            long read = drain(response.body());
            bytesRead.add(read);
            requests.increment();
            viewerBytes += read;
            offset += read;

            if (read < chunkBytes) {
                offset = 0; // Reached the end; loop the file rather than stop.
            }

            if ("realtime".equals(StreamLoadTest.mode)) {
                // Pace to the bitrate this viewer has actually been served, so
                // the run settles at "watching" rather than "downloading".
                double elapsed = (System.currentTimeMillis() - viewerStart) / 1000.0;
                double shouldHaveRead = elapsed * assumedBitsPerSecond() / 8.0;
                if (viewerBytes > shouldHaveRead) {
                    long sleep = (long) ((viewerBytes - shouldHaveRead) / (assumedBitsPerSecond() / 8.0) * 1000);
                    Thread.sleep(Math.min(sleep, 5000));
                }
            }
        }

        double achieved = viewerBytes * 8.0 / Math.max(1, (System.currentTimeMillis() - viewerStart) / 1000.0);
        if (achieved >= assumedBitsPerSecond() * 0.95) {
            viewersKeptUp.increment();
        } else {
            viewersFellBehind.increment();
        }
    }

    /** What one viewer needs per second. 1.6 Mbps is this library's 536p direct play. */
    private static double assumedBitsPerSecond() {
        return 1_600_000.0;
    }

    private static long drain(InputStream in) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        try (in) {
            while ((n = in.read(buffer)) != -1) {
                total += n;
            }
        }
        return total;
    }

    // --- setup -------------------------------------------------------------

    private static String login(HttpClient client) throws Exception {
        String body = "{\"usernameOrEmail\":\"" + user + "\",\"password\":\"" + password + "\"}";
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Login failed: " + response.statusCode() + " " + response.body());
        }
        return field(response.body(), "token");
    }

    private static String firstProfile(HttpClient client) throws Exception {
        HttpResponse<String> response = client.send(
                authed(HttpRequest.newBuilder(URI.create(base + "/api/profiles"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not list profiles: " + response.statusCode());
        }
        return field(response.body(), "id");
    }

    private static HttpRequest.Builder authed(HttpRequest.Builder builder) {
        builder.header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(60));
        if (profileId != null) {
            builder.header("X-Profile-Id", profileId);
        }
        return builder;
    }

    /** Enough JSON for four flat string fields. Adding a parser dependency to a one-file script is not worth it. */
    private static String field(String json, String name) {
        String key = "\"" + name + "\":\"";
        int at = json.indexOf(key);
        if (at < 0) {
            return null;
        }
        int from = at + key.length();
        return json.substring(from, json.indexOf('"', from));
    }

    private static void count(String what) {
        failures.computeIfAbsent(what, k -> new LongAdder()).increment();
    }

    private static long totalFailures() {
        return failures.values().stream().mapToLong(LongAdder::sum).sum();
    }

    private static void parse(String[] args) {
        for (int i = 0; i < args.length - 1; i += 2) {
            String value = args[i + 1];
            switch (args[i]) {
                case "--base" -> base = value.replaceAll("/+$", "");
                case "--user" -> user = value;
                case "--password" -> password = value;
                case "--token" -> token = value;
                case "--profile" -> profileId = value;
                case "--item" -> itemId = value;
                case "--viewers" -> viewers = Integer.parseInt(value);
                case "--seconds" -> seconds = Integer.parseInt(value);
                case "--ramp" -> rampSeconds = Integer.parseInt(value);
                case "--mode" -> mode = value;
                case "--chunk-mb" -> chunkBytes = Long.parseLong(value) * 1024 * 1024;
                default -> System.err.println("Ignoring unknown argument " + args[i]);
            }
        }
    }

    // --- results -----------------------------------------------------------

    private static void report(double millis) {
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Comparator.naturalOrder());

        double seconds = millis / 1000.0;
        double mbps = bytesRead.sum() * 8.0 / seconds / 1_000_000.0;

        System.out.printf("%n--- results ------------------------------------------%n");
        System.out.printf("  ran for            %.1fs%n", seconds);
        System.out.printf("  decisions          %d direct, %d transcode%n",
                decisionsDirect.get(), decisionsTranscode.get());
        System.out.printf("  range requests     %d%n", requests.sum());
        System.out.printf("  served             %.2f GB%n", bytesRead.sum() / 1e9);
        System.out.printf("  throughput         %.1f Mbps sustained%n", mbps);
        if (!sorted.isEmpty()) {
            System.out.printf("  time to first byte p50 %dms  p95 %dms  p99 %dms  max %dms%n",
                    percentile(sorted, 50), percentile(sorted, 95),
                    percentile(sorted, 99), sorted.get(sorted.size() - 1));
        }
        if ("realtime".equals(mode)) {
            System.out.printf("  kept up            %d of %d viewers%n",
                    viewersKeptUp.sum(), viewersKeptUp.sum() + viewersFellBehind.sum());
        }
        if (failures.isEmpty()) {
            System.out.printf("  errors             none%n");
        } else {
            System.out.printf("  errors%n");
            failures.forEach((what, n) -> System.out.printf("    %-24s %d%n", what, n.sum()));
        }
        System.out.printf("%n  For scale: %.0f viewers at 1.6 Mbps each is %.0f Mbps.%n",
                (double) viewers, viewers * 1.6);
        System.out.printf("  Over the internet the household's upstream is the ceiling,%n");
        System.out.printf("  whatever this machine can do on its own network.%n%n");
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
