package com.example.kido.mymirror;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * How the backend obtains and refreshes the one global mirror session it replays on every
 * upstream call (see {@link MirrorSessionProvider}).
 *
 * <p>A NetMirror session ({@code t_hash_t} + {@code cf_clearance}) is minted only when a human
 * watches the ad at the gate — it cannot be faked or automated. The capture tooling under
 * {@code tools/netmirror-capture} opens a real browser for that single ad-view and writes the
 * earned session to {@code cdp-capture/session.txt}. This backend holds that session in memory and
 * can ask the tooling to earn a fresh one on demand.
 *
 * <p><b>Fail-closed:</b> {@link #spawnEnabled} is {@code false} by default, so no browser is ever
 * launched unless an operator turns it on. With it off the provider still loads and replays an
 * existing {@code session.txt}, but never spawns the tooling — which keeps tests, CI, and
 * unattended runs from opening a window. Turn it on only where a human is present to clear the gate.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.mymirror.session")
public class MirrorSessionProperties {

    /**
     * Allow the backend to spawn the capture tooling to earn/refresh the global session. Off by
     * default: with it off the provider replays an existing session.txt but never opens a browser.
     */
    private boolean spawnEnabled = false;

    /** Directory holding the capture tooling, relative to the backend's working directory (or absolute). */
    private String toolDir = "tools/netmirror-capture";

    /** The supervisor script to run for one earn/refresh cycle. */
    private String script = "run-discovery.mjs";

    /** The Node.js executable (Node 22+ is required by the tooling). */
    private String node = "node";

    /**
     * The earned-session file the tooling writes and the provider reads. Blank resolves to
     * {@code <toolDir>/cdp-capture/session.txt}, the tooling's own default.
     */
    private String sessionFile = "";

    /**
     * How long to wait for one spawned cycle before giving up on it. The headless reuse path is
     * quick; the human ad-view path can take minutes, so this is generous. A refresh that exceeds
     * it is abandoned and the process destroyed, freeing the single-refresh slot for a later try.
     */
    private Duration refreshTimeout = Duration.ofMinutes(10);

    /**
     * Keep the earned session warm by polling the ad-gate on a timer, the way the real NetMirror app
     * does (its home WebView re-POSTs {@code verify2.php} every couple of seconds). Upstream drops an
     * idle {@code t_hash_t} session after ~10 minutes of silence; without this, a session earned at an
     * honest ad-view goes cold between bursts of app traffic and has to be re-earned. On by default:
     * the poll spawns nothing, it only replays the session we already hold.
     */
    private boolean keepAliveEnabled = true;

    /**
     * How often to poll the ad-gate to keep the held session warm. Well under upstream's ~10-minute
     * idle timeout, and far gentler than the app's ~2-second cadence — a human watching a show is
     * already refreshing the session through playback traffic, so this only has to cover idle gaps.
     */
    private Duration keepAliveInterval = Duration.ofMinutes(2);

    /**
     * Keep the replayed Cloudflare {@code cf_clearance} fresh by re-minting it on a timer before its
     * ~30-minute TTL lapses, so content calls never land in the gap between an expired clearance and
     * the next observed challenge. On by default; it acts only while a clearance is actually held
     * (Cloudflare is gating), so it opens no browser when Cloudflare is quiet. Like every spawn, it
     * is also a no-op unless {@link #spawnEnabled} is on.
     */
    private boolean clearanceRefreshEnabled = true;

    /**
     * How often to re-mint the held {@code cf_clearance}. Kept safely under Cloudflare's ~30-minute
     * TTL so a fresh clearance is always in hand before the old one dies. The re-mint reuses the held
     * {@code t_hash_t}, so it runs without a human while the ad-gate session is alive.
     */
    private Duration clearanceRefreshInterval = Duration.ofMinutes(25);

    /**
     * Rotate the ad-gate session <em>before</em> it dies, rather than waiting for it to be rejected.
     * The {@code t_hash_t} session has a hard ~15–16 minute lifetime from the moment it is minted that
     * the keep-alive poll does <b>not</b> extend: {@code verify2.php} reports the gate's cleared/uncleared
     * <em>status</em> but never renews the TTL, so a session dies on schedule even under a steady
     * "gate still cleared" poll. The old reactive path only noticed at death — it nulled the session and
     * re-earned, leaving a ~2-minute window where the rail returned empty and the app showed "Mirror not
     * connected." This rotates ahead of the deadline while the current session still serves, so the
     * re-earn swaps in atomically (see {@link MirrorSessionProvider} {@code runCapture}, which sets the
     * fresh cookie only on success and never nulls first) with no empty-rail gap. On by default, but like
     * every re-earn it is a no-op unless {@link #spawnEnabled} is on and a human is present to clear the gate.
     */
    private boolean proactiveRefreshEnabled = true;

    /**
     * How old the held session may get before proactive rotation re-earns it. Set safely under the
     * observed ~15–16 minute hard {@code t_hash_t} TTL so the fresh session is in hand before the old
     * one dies, with headroom for the ~2-minute earn itself. Tunable: lower it if upstream shortens the
     * lifetime, raise it toward (but never to) the hard TTL to re-earn less often.
     */
    private Duration sessionMaxAge = Duration.ofMinutes(12);

    /**
     * How often the rotation check runs. It only compares the held session's age against
     * {@link #sessionMaxAge} and triggers at most one re-earn, so a tight cadence is cheap and keeps the
     * actual rotation close to the deadline. Kept well under {@link #sessionMaxAge} so the check never
     * straddles the threshold by more than one tick.
     */
    private Duration proactiveRefreshInterval = Duration.ofMinutes(1);
}
