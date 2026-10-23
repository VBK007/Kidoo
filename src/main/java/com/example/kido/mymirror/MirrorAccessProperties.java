package com.example.kido.mymirror;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * How the mirror flow gets past Cloudflare.
 *
 * <p>The backend rotated (net52.cc → net77.cc) and now sits behind a Cloudflare "Just a moment..."
 * JS challenge, so a plain HTTP client earns a 403 on everything past {@code /check.php}. The
 * challenge can only be solved by a real browser engine, which mints a {@code cf_clearance} cookie
 * bound to the browser's User-Agent <em>and</em> source IP. This app is not a browser, so it cannot
 * solve the challenge itself; instead it <em>replays</em> a {@code cf_clearance} harvested from the
 * device's WebView (see {@code ReverseEngineering/frida-tool/dump-cookies.js}).
 *
 * <p>Everything here is off by default, so with no configuration the flow behaves exactly as before
 * (and will 403 against the live CF-gated backend). To reach content, set {@code
 * app.mymirror.access.cf-clearance} to a freshly harvested token and {@code
 * app.mymirror.access.browser-user-agent} to the <em>exact</em> UA of the WebView that minted it —
 * Cloudflare rejects the cookie if the UA differs — then run the server from the same public IP, as
 * {@code cf_clearance} is IP-bound and short-lived (~30 min).
 *
 * <p>A per-request {@code cf_clearance} carried in the session cookie always wins over the one
 * configured here, so a client that harvests its own fresher token is never overridden.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.mymirror.access")
public class MirrorAccessProperties {

    /** Master switch for the Cloudflare-replay behaviour. Nothing below takes effect when false. */
    private boolean enabled = true;

    /**
     * A {@code cf_clearance} cookie value harvested from the device WebView, replayed on every
     * upstream request that does not already carry one. Blank (the default) means none is injected.
     */
    private String cfClearance = "";

    /**
     * The exact User-Agent the {@code cf_clearance} was minted with. When set and a cf_clearance is
     * in play, it is pinned on the upstream request, because Cloudflare ties the cookie to the UA
     * and rejects a mismatch. Blank leaves the caller's own UA in place.
     */
    private String browserUserAgent = "";

    /**
     * Follow the backend's {@code net52 → net77} redirect at discovery time and hang the rest of the
     * flow off the landed origin, rather than trusting the stale origin decoded from the dispatcher's
     * {@code token_hash}. Off by default: it costs one extra probe per discovery and makes a live
     * network call, so enable it only against the real, rotated backend.
     */
    private boolean followRedirects = false;
}
