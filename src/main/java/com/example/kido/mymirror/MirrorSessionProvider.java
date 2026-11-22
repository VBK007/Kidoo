package com.example.kido.mymirror;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import com.example.kido.mymirror.repo.AdGateRepository;
import com.example.kido.mymirror.service.DispatcherService;

import lombok.extern.slf4j.Slf4j;

/**
 * The one global mirror session this backend replays on every upstream call.
 *
 * <p>A NetMirror session — the {@code t_hash_t} ad-gate cookie plus the Cloudflare
 * {@code cf_clearance} — is minted only when a human watches the ad at the gate. It cannot be faked
 * or automated (faking the impression is ad fraud; replaying a dead one is rejected upstream). The
 * capture tooling under {@code tools/netmirror-capture} opens a real browser for that single honest
 * ad-view and writes the earned session to {@code cdp-capture/session.txt}.
 *
 * <p>This component holds that earned session in memory and hands it to {@link MyMirrorConfig}'s
 * cookie resolver as the fallback for any mirror request that does not carry its own cookie. When
 * there is no session (or one has just been rejected), it can ask the tooling to earn a fresh one —
 * which opens the browser so a human can clear the gate. That acquisition is:
 *
 * <ul>
 *   <li><b>non-blocking</b> — the triggering request returns immediately with an "acquiring"
 *       status; the human clears the gate, and a later request picks up the fresh session;</li>
 *   <li><b>serialized</b> — only one acquisition runs at a time, so a burst of requests never opens
 *       a stack of browser windows;</li>
 *   <li><b>fail-closed</b> — it spawns nothing unless {@link MirrorSessionProperties#isSpawnEnabled()}
 *       is on, so tests, CI, and unattended runs replay an existing session.txt but never open a
 *       window;</li>
 *   <li><b>UA-coupled</b> — it passes the backend's configured {@code browserUserAgent} to the
 *       tooling as {@code MIRROR_BROWSER_UA}, so the {@code cf_clearance} is minted with the exact
 *       UA the backend then pins on upstream calls ({@link CloudflareAccessInterceptor}).</li>
 * </ul>
 *
 * <p>The session value is a secret: it is held only in memory and never written to the log. The
 * tooling owns the on-disk {@code session.txt} (mode 600, gitignored).
 */
@Slf4j
@Component
public class MirrorSessionProvider {

    private static final String SESSION_MARKER = "t_hash_t=";
    private static final String CLEARANCE_MARKER = "cf_clearance=";
    // How the mirror signals the gate is still cleared on a verify2.php poll: uncleared it answers
    // {"c":"n","statusup":"Waiting for your ads click."}, cleared it flips either field. Same markers
    // TokenManager reads; duplicated here so the keep-alive poll does not depend on that component.
    private static final String GATE_CLEARED_STATUS = "\"statusup\":\"OK\"";
    private static final String GATE_CLEARED_FLAG = "\"c\":\"y\"";

    private final MirrorSessionProperties session;
    private final MirrorAccessProperties access;
    private final AdGateRepository adGate;
    private final DispatcherService dispatcher;

    /** The earned session cookie, in memory only. {@code null} when none is held. Never logged. */
    private volatile String sessionCookie;

    /**
     * When the held {@code t_hash_t} was minted ({@code System.currentTimeMillis()}), so proactive
     * rotation can re-earn it before its hard ~15–16 minute TTL. Set from the session file's mtime on
     * load and to "now" on every fresh earn; {@code 0} means no session is held. The cookie carries no
     * readable expiry, so this clock is the backend's only handle on the session's age.
     */
    private volatile long earnedAtMillis;

    /** Guards the single-acquisition rule: true while a spawn is in flight. */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mirror-session-refresh");
                t.setDaemon(true);
                return t;
            });
    /** The in-flight capture process, so shutdown and timeout can tear it down. */
    private volatile Process active;

    public MirrorSessionProvider(MirrorSessionProperties session, MirrorAccessProperties access,
                                 AdGateRepository adGate, DispatcherService dispatcher) {
        this.session = session;
        this.access = access;
        this.adGate = adGate;
        this.dispatcher = dispatcher;
    }

    /** Load a session the tooling left on disk from a previous run, if any. */
    @PostConstruct
    void load() {
        String loaded = readSessionFile();
        if (loaded != null) {
            sessionCookie = loaded;
            earnedAtMillis = sessionFileMintedAt();
            log.info("Loaded a saved mirror session ({} chars) from {}", loaded.length(), sessionFile());
        } else {
            log.info("No saved mirror session at {}; it will be earned on demand (spawn enabled: {})",
                    sessionFile(), session.isSpawnEnabled());
        }
    }

    /** The held global session cookie, or {@code null} when none is available. Never triggers work. */
    public String currentCookie() {
        return sessionCookie;
    }

    /**
     * The held global session, or {@code null} — kicking off an acquisition when none is held so a
     * later request can succeed. Call this from the request path when the caller sent no cookie of
     * its own.
     */
    public String ensureSession() {
        String held = sessionCookie;
        if (held != null) {
            return held;
        }
        triggerRefresh("no session held");
        return null;
    }

    /**
     * Note that a cookie the upstream mirror rejected (expired gate, stale cf_clearance) was this
     * global session, and earn a fresh one. A per-request cookie the caller supplied is not ours, so
     * it is ignored here.
     */
    public void noteRejected(String cookie) {
        if (cookie != null && cookie.equals(sessionCookie)) {
            log.info("Global mirror session was rejected upstream; dropping it and re-earning");
            sessionCookie = null;
            earnedAtMillis = 0;
            triggerRefresh("session rejected upstream", true);
        }
    }

    /**
     * Note that an upstream call came back as a Cloudflare challenge rather than a dead ad-gate (see
     * {@link MirrorCloudflare}). The {@code t_hash_t} ad-gate session is still good — only the
     * short-lived {@code cf_clearance} has lapsed, or Cloudflare has started gating this IP — so the
     * held session is <b>kept</b> and a capture cycle is kicked off to re-mint the clearance. Reusing
     * the live {@code t_hash_t}, that cycle runs without a human. A transient challenge therefore
     * never throws away a valid gate; at worst it costs one headless re-mint.
     */
    public void noteCloudflareChallenge() {
        if (sessionCookie == null || refreshing.get()) {
            return;
        }
        log.info("Upstream returned a Cloudflare challenge; re-minting cf_clearance (ad-gate session kept)");
        triggerRefresh("cloudflare challenge — cf_clearance re-mint");
    }

    /** Whether the held session currently carries a Cloudflare {@code cf_clearance}. */
    private boolean holdsClearance() {
        String held = sessionCookie;
        return held != null && held.contains(CLEARANCE_MARKER);
    }

    /** The {@code t_hash_t} ad-gate cookie value within a session string, or {@code null} if absent. */
    private static String tHashValue(String cookie) {
        if (cookie == null) return null;
        int at = cookie.indexOf(SESSION_MARKER);
        if (at < 0) return null;
        int start = at + SESSION_MARKER.length();
        int end = cookie.indexOf(';', start);
        return (end < 0 ? cookie.substring(start) : cookie.substring(start, end)).trim();
    }

    /**
     * Whether two session strings carry the same ad-gate cookie — i.e. no genuine re-earn happened.
     * Compares only the {@code t_hash_t} value, so a refreshed {@code cf_clearance} on an unchanged gate
     * is <em>not</em> treated as a new session. A {@code null}/gate-less argument never matches.
     */
    private static boolean sameGate(String a, String b) {
        String ta = tHashValue(a);
        return ta != null && ta.equals(tHashValue(b));
    }

    /** Whether an acquisition is currently in flight (a human may be clearing the gate). */
    public boolean isAcquiring() {
        return refreshing.get();
    }

    /**
     * Keep the held session warm, the way the real app does. NetMirror's home WebView re-POSTs
     * {@code verify2.php} every couple of seconds; that steady poll is the whole reason its session
     * never lapses — the token itself never changes and carries no future expiry, upstream simply
     * drops a {@code t_hash_t} that goes quiet for ~10 minutes. This backend earns a session once and
     * then only touches the gate on demand, so between bursts of app traffic the session goes cold and
     * has to be re-earned at a fresh ad-view. Replaying the same {@code verify2.php} POST on a timer
     * well inside that window keeps the earned session alive indefinitely.
     *
     * <p>Only runs when a session is held and no acquisition is in flight. It re-POSTs the gate purely
     * to keep it warm; the response is never read as a drop signal. An uncleared answer
     * ({@code "c":"n"}, "Waiting for your ads click.") is the gate's normal resting state once an
     * ad-view's cleared window lapses — not proof the cookie died, which still authorizes content well
     * past that point — so the session is kept, not dropped (see the body). A Cloudflare challenge
     * re-mints {@code cf_clearance} and keeps the session; a transient failure (discovery down, a
     * network blip) is logged and left alone. Session lifetime is bounded elsewhere: age-based rotation
     * ({@link #rotateBeforeExpiry}) and a genuinely token-less upstream (the 502 drop in TokenManager).
     */
    @Scheduled(initialDelayString = "${app.mymirror.session.keep-alive-interval:2m}",
               fixedDelayString = "${app.mymirror.session.keep-alive-interval:2m}")
    void keepSessionWarm() {
        if (!session.isKeepAliveEnabled()) return;
        String held = sessionCookie;
        if (held == null || refreshing.get()) return;
        try {
            String response = adGate.verifyAds(dispatcher.resolveActiveBaseUrl(), held);
            if (response != null
                    && (response.contains(GATE_CLEARED_STATUS) || response.contains(GATE_CLEARED_FLAG))) {
                log.debug("Mirror session keep-alive: gate still cleared");
                return;
            }
            // Gate reports uncleared (c:n, "Waiting for your ads click.") — its normal resting state
            // once an ad-view's cleared window lapses, NOT proof the session cookie is dead. The cookie
            // keeps authorizing content well past that point (observed streaming AND browsing on a
            // 48-minute-old session while this very poll read c:n), so dropping it here would throw away
            // a working session every couple of minutes and strand the app at 503 — precisely the
            // "session dies after a few minutes" symptom. The gate response cannot tell a resting
            // session from a truly dead one, so it is not a safe drop signal. Session lifetime is bounded
            // instead by age-based rotation (rotateBeforeExpiry) and genuine upstream failures (a
            // Cloudflare challenge → cf_clearance re-mint, a token-less 502 in TokenManager). So keep the
            // session and only record the state.
            log.debug("Mirror session keep-alive: gate reports uncleared (resting state); keeping the session");
        } catch (RuntimeException e) {
            // A Cloudflare challenge is not proof the gate died — the t_hash_t is still good, only the
            // cf_clearance lapsed. Re-mint the clearance and keep the session, rather than reading the
            // block as an uncleared gate and dropping a session that is fine.
            if (MirrorCloudflare.isChallenge(e)) {
                log.info("Mirror session keep-alive hit a Cloudflare challenge; re-minting cf_clearance (session kept)");
                noteCloudflareChallenge();
                return;
            }
            // A blip (discovery down, upstream hiccup) is not proof the session died; keep it. The only
            // thing that drops the session is a genuinely token-less upstream answer (the 502 path in
            // TokenManager) or age-based rotation — never a failed keep-alive poll.
            log.debug("Mirror session keep-alive poll failed (keeping the session): {}", e.getMessage());
        }
    }

    /**
     * Re-mint the Cloudflare {@code cf_clearance} on a timer, before its ~30-minute TTL lapses, so a
     * content call never lands in the gap between an expired clearance and the next observed challenge.
     * The keep-alive above keeps the {@code t_hash_t} ad-gate warm, but a live gate still fails every
     * upstream call once its {@code cf_clearance} dies; this closes that window proactively instead of
     * waiting for the first 403/503 to force a re-mint on the request path.
     *
     * <p>Acts only while a clearance is actually held — i.e. Cloudflare is gating and we have a
     * clearance worth renewing — so it opens no browser when Cloudflare is quiet and the held session
     * is a bare {@code t_hash_t}. The re-mint reuses the live {@code t_hash_t}, so it runs without a
     * human, and (like every spawn) is a no-op unless spawning is enabled.
     */
    @Scheduled(initialDelayString = "${app.mymirror.session.clearance-refresh-interval:25m}",
               fixedDelayString = "${app.mymirror.session.clearance-refresh-interval:25m}")
    void refreshClearance() {
        if (!session.isClearanceRefreshEnabled()) return;
        if (!holdsClearance() || refreshing.get()) return;
        log.info("Proactively re-minting cf_clearance before its TTL lapses (ad-gate session kept)");
        triggerRefresh("proactive cf_clearance refresh before TTL");
    }

    /**
     * Rotate the ad-gate session <em>before</em> its hard ~15–16 minute {@code t_hash_t} TTL expires,
     * rather than waiting for the keep-alive poll to read an uncleared gate after the session is already
     * dead. The TTL is a fixed lifetime from mint that {@code verify2.php} polling does not extend, so a
     * session dies on schedule even while the gate "still reports cleared"; the old reactive path only
     * noticed at death, when it nulled the session and re-earned, opening a ~2-minute window where the
     * rail returned empty and the app showed "Mirror not connected."
     *
     * <p>This fires on a short cadence and, once the held session reaches {@link
     * MirrorSessionProperties#getSessionMaxAge()} (set safely under the hard TTL), kicks off a re-earn
     * <b>without</b> nulling the current session. {@link #runCapture(boolean)} swaps in the fresh cookie only on
     * success (never nulls first), so the old, still-valid session keeps serving the rail throughout the
     * ~2-minute earn and the swap is seamless — no empty-rail gap. Only runs with a session held, no
     * acquisition already in flight, and (like every re-earn) a no-op unless spawning is enabled.
     */
    @Scheduled(initialDelayString = "${app.mymirror.session.proactive-refresh-interval:1m}",
               fixedDelayString = "${app.mymirror.session.proactive-refresh-interval:1m}")
    void rotateBeforeExpiry() {
        if (!session.isProactiveRefreshEnabled()) return;
        if (sessionCookie == null || refreshing.get()) return;
        long bornAt = earnedAtMillis;
        if (bornAt <= 0) return;
        long age = System.currentTimeMillis() - bornAt;
        if (age < session.getSessionMaxAge().toMillis()) return;
        log.info("Mirror session has reached {}ms (rotate-before threshold {}); re-earning ahead of its hard TTL "
                        + "while the current session keeps serving",
                age, session.getSessionMaxAge());
        triggerRefresh("proactive rotation before t_hash_t TTL", true);
    }

    /**
     * Why no global session is available right now, phrased honestly for the 503 the resolver
     * returns. When spawning is enabled the backend opens the gate itself, so it points the human at
     * that browser; when it is disabled (the fail-closed default) nothing opens automatically, so it
     * says an operator must earn one rather than naming a browser that was never opened.
     */
    public String unavailableReason() {
        return session.isSpawnEnabled()
                ? "The mirror session is being acquired; clear the ad gate in the opened browser, then retry"
                : "No mirror session is available; an operator must earn one at the ad gate, then retry";
    }

    /**
     * Start one acquisition if the tooling is enabled and none is already running. Returns at once;
     * the browser (when a fresh session must be earned) opens on the worker thread.
     */
    private void triggerRefresh(String why) {
        triggerRefresh(why, false);
    }

    /**
     * Start one acquisition, optionally forcing a fresh earn. With {@code forceEarn} false the tooling
     * reuses a still-valid saved session when one exists (the clearance-re-mint and bootstrap paths);
     * with it true the tooling skips that reuse and mints a brand-new {@code t_hash_t} at the gate.
     * Rotation and rejection pass {@code true}: reuse would defeat them — rotation must replace the
     * ad-gate cookie <em>before</em> its hard TTL (a headless reuse earns nothing and the session dies
     * on schedule), and rejection has already dropped the dead cookie.
     */
    private void triggerRefresh(String why, boolean forceEarn) {
        if (!session.isSpawnEnabled()) {
            log.debug("Mirror session needs earning ({}) but spawning is disabled", why);
            return;
        }
        if (!refreshing.compareAndSet(false, true)) {
            log.debug("Mirror session acquisition already in flight; not starting another ({})", why);
            return;
        }
        log.info("Earning a mirror session ({}) — the capture tooling will open the gate for one ad-view", why);
        worker.submit(() -> {
            try {
                runCapture(forceEarn);
            } catch (Exception e) {
                log.warn("Mirror session acquisition failed: {}", e.getMessage());
            } finally {
                refreshing.set(false);
            }
        });
    }

    /** Spawn one earn/refresh cycle of the capture tooling and, on success, reload the saved session. */
    private void runCapture(boolean forceEarn) throws IOException, InterruptedException {
        Path toolDir = Path.of(session.getToolDir()).toAbsolutePath().normalize();
        Path script = toolDir.resolve(session.getScript());
        if (!Files.isRegularFile(script)) {
            log.warn("Mirror capture script not found at {}; cannot earn a session", script);
            return;
        }

        List<String> command = new ArrayList<>(List.of(session.getNode(), script.toString()));
        if (forceEarn) {
            // Rotation/rejection: tell the supervisor to mint a NEW session instead of reusing the saved
            // one. Without this the headless reuse succeeds, earns no fresh t_hash_t, and the gate dies
            // at its hard TTL — exactly the expiry proactive rotation exists to prevent.
            command.add("--force-earn");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(toolDir.toFile());
        // Couple the minted cf_clearance to the UA the backend pins upstream (the tooling reads this
        // first). Without it the tooling falls back to its own default UA and the clearance may not
        // validate against the backend's configured browserUserAgent.
        String ua = access.getBrowserUserAgent();
        if (ua != null && !ua.isBlank()) {
            pb.environment().put("MIRROR_BROWSER_UA", ua.trim());
        }
        // Inherit stdio so a present human sees the "opening the gate for ONE ad-view" prompt. The
        // tooling logs only the session file path, never the token value.
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process process = pb.start();
        active = process;
        try {
            boolean done = process.waitFor(session.getRefreshTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                log.warn("Mirror capture did not finish within {}; abandoning it", session.getRefreshTimeout());
                process.destroyForcibly();
                return;
            }
            int exit = process.exitValue();
            // Trust the artifact, not the exit code. The --force-earn supervisor mints the session and
            // THEN runs a best-effort discovery probe in the same process; that probe can exit nonzero
            // (e.g. a skin renders no cards) long after a fresh t_hash_t was cleared at the gate and
            // written to session.txt. Keying "did we earn?" off the exit code alone threw that
            // freshly-minted session away and let rotation re-open the gate every minute. So read the
            // saved session regardless of exit and adopt it whenever the ad-gate cookie actually changed —
            // a new t_hash_t value only ever reaches disk after a real gate clearance.
            String fresh = readSessionFile();
            boolean gateRenewed = fresh != null && !sameGate(fresh, sessionCookie);
            if (exit != 0 && !gateRenewed) {
                // A genuine failure: no new gate cookie reached disk. Leave the held session untouched.
                log.warn("Mirror capture exited {} — no fresh session earned", exit);
                return;
            }
            if (fresh == null) {
                log.warn("Mirror capture reported success but left no readable session at {}", sessionFile());
                return;
            }
            if (exit != 0) {
                // The gate was cleared and a new t_hash_t persisted, but the process exited nonzero
                // afterward (the post-earn discovery probe). The earn is what matters here; salvage it
                // instead of discarding a valid session and nagging for another ad-view a minute later.
                log.info("Mirror capture exited {} after minting a fresh session; adopting the earned gate "
                        + "cookie (the post-earn probe failed, the earn did not)", exit);
            }
            // Reset the age clock only when the ad-gate cookie actually changed. A clearance-only re-mint
            // or a headless reuse rewrites session.txt (bumping its mtime) while the same t_hash_t is
            // retained; resetting the clock then would mask the real age and let the gate die at its hard
            // TTL without rotation ever firing. Compare the t_hash_t value itself, not the whole string, so
            // a new cf_clearance on an unchanged gate does not count as a re-earn.
            sessionCookie = fresh;
            if (gateRenewed) {
                earnedAtMillis = System.currentTimeMillis();
                log.info("Earned a fresh mirror session ({} chars)", fresh.length());
            } else {
                log.info("Mirror capture refreshed the session ({} chars) but the t_hash_t ad-gate is "
                        + "unchanged (clearance re-mint/reuse); keeping its age of {}ms",
                        fresh.length(), System.currentTimeMillis() - earnedAtMillis);
            }
        } finally {
            active = null;
        }
    }

    /**
     * When the saved session file was last written, as a {@code System.currentTimeMillis()}-comparable
     * epoch milli — the best available proxy for when the held {@code t_hash_t} was minted. Falls back
     * to "now" if the mtime cannot be read, so a just-loaded session is treated as fresh rather than
     * instantly stale (which would force an immediate re-earn on boot).
     */
    private long sessionFileMintedAt() {
        Path file = sessionFile();
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            log.debug("Could not read mtime of {}; treating the loaded session as freshly earned: {}",
                    file, e.getMessage());
            return System.currentTimeMillis();
        }
    }

    /** The resolved session file: the configured one, or the tooling's default under the tool dir. */
    private Path sessionFile() {
        String configured = session.getSessionFile();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(session.getToolDir(), "cdp-capture", "session.txt").toAbsolutePath().normalize();
    }

    /** Read and validate the saved session file, or {@code null} when absent/empty/malformed. */
    private String readSessionFile() {
        Path file = sessionFile();
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (content.isEmpty() || !content.contains(SESSION_MARKER)) {
                return null;
            }
            // A CR/LF in a replayed cookie would let it inject upstream headers; the interceptor and
            // resolver reject those, but screen them here too so a malformed file is treated as none.
            if (content.indexOf('\r') >= 0 || content.indexOf('\n') >= 0) {
                log.warn("Saved mirror session at {} spans multiple lines; ignoring it", file);
                return null;
            }
            return content;
        } catch (IOException e) {
            log.warn("Could not read the saved mirror session at {}: {}", file, e.getMessage());
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
        Process p = active;
        if (p != null) {
            p.destroyForcibly();
        }
    }
}
