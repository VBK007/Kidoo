package com.example.kido.mymirror;

import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * Recognises a Cloudflare "Just a moment…" challenge in a failed upstream response, so the mirror
 * flow can tell a short-lived <em>access</em> block apart from a genuinely dead <em>ad-gate</em>.
 *
 * <p>NetMirror sits behind a Cloudflare JS/managed challenge. When the replayed {@code cf_clearance}
 * lapses (~30 min, IP- and UA-bound) or Cloudflare starts gating the backend's IP, every upstream
 * call comes back as a challenge — classically {@code 503}, {@code 403} on the managed variant,
 * sometimes {@code 429} — carrying the interstitial HTML or a {@code cf-mitigated} / {@code cf-ray}
 * header. The ad-gate session ({@code t_hash_t}) is untouched by this; only the Cloudflare layer
 * needs renewing, which a real browser does by solving the challenge afresh.
 *
 * <p>The distinction matters: a challenge must trigger a {@code cf_clearance} re-mint while keeping
 * the still-valid {@code t_hash_t}, whereas a dead gate ({@code 412}) must drop the session and
 * re-earn it at a human ad-view. Misreading one for the other either throws away a good session or
 * loops on a session the backend keeps rejecting.
 */
public final class MirrorCloudflare {

    private MirrorCloudflare() {
    }

    // Phrases Cloudflare's interstitial carries. Matched case-insensitively and only alongside a
    // challenge status, so an ordinary page that merely names Cloudflare is not misread as a block.
    private static final String[] BODY_MARKERS = {
            "just a moment", "checking your browser", "cf-browser-verification",
            "attention required", "__cf_chl", "cloudflare",
    };

    /**
     * Whether a failed upstream response is a Cloudflare challenge rather than a real application
     * answer. Requires both a challenge status ({@code 403}/{@code 503}/{@code 429}) and a Cloudflare
     * fingerprint — a {@code cf-mitigated}/{@code cf-ray} header or the interstitial HTML — so a plain
     * upstream {@code 403}/{@code 503} that is not Cloudflare's doing is left to its normal handling.
     */
    public static boolean isChallenge(HttpStatusCodeException e) {
        if (e == null) return false;
        int code = e.getStatusCode().value();
        if (code != 403 && code != 503 && code != 429) return false;
        return hasCloudflareHeader(e.getResponseHeaders()) || hasChallengeBody(e.getResponseBodyAsString());
    }

    /** As {@link #isChallenge(HttpStatusCodeException)}, for a caught {@link Throwable} of any type. */
    public static boolean isChallenge(Throwable t) {
        return t instanceof HttpStatusCodeException e && isChallenge(e);
    }

    private static boolean hasCloudflareHeader(HttpHeaders headers) {
        if (headers == null) return false;
        if (headers.containsHeader("cf-mitigated")) return true;
        List<String> server = headers.get(HttpHeaders.SERVER);
        boolean cfServer = server != null
                && server.stream().anyMatch(s -> s != null && s.toLowerCase(Locale.ROOT).contains("cloudflare"));
        return cfServer && headers.containsHeader("cf-ray");
    }

    private static boolean hasChallengeBody(String body) {
        if (body == null || body.isEmpty()) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        for (String marker : BODY_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }
}
