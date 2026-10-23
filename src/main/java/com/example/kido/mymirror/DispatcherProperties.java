package com.example.kido.mymirror;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * The mirror pool the dispatcher walks to discover the active backend, and how patient it is.
 *
 * <p>The app ships a hard-coded pool of interchangeable "mobile detect" domains and asks each, in
 * turn, for {@code /check.php} until a reachable one answers with the backend config. Keeping the
 * pool in configuration (rather than compiled in) means a mirror can be added or dropped without a
 * rebuild when the upstream rotates its domains — which it does often, because these hosts get
 * blocked (at the time of writing only one of them answered; the rest returned 403).
 *
 * <p>Everything here has a working default, so discovery runs with no {@code application.properties}
 * entries at all; override {@code app.mymirror.dispatcher.*} only to point at a different pool or to
 * tune the timeouts.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.mymirror.dispatcher")
public class DispatcherProperties {

    /**
     * The mirror pool, tried in order. The first one that returns a usable {@code /check.php} body
     * wins, so order is a priority list: put the most reliable hosts first. These 23 are the pool
     * observed in the shipped app bundle. {@code mobiledetect.app} is listed first because a live
     * re-verify (2026-10-23) found it was the only host still answering {@code /check.php}; the rest
     * returned 403/404 but are kept as fallbacks in case the upstream rotates again.
     */
    private List<String> mirrors = List.of(
            "https://mobiledetect.app",
            "https://mobidetect.art",
            "https://mobidetect.cc",
            "https://mobidetect.click",
            "https://mobidetect.ink",
            "https://mobidetect.live",
            "https://mobidetect.pro",
            "https://mobidetect.shop",
            "https://mobidetect.site",
            "https://mobidetect.space",
            "https://mobidetect.store",
            "https://mobidetect.vip",
            "https://mobidetect.wiki",
            "https://mobidetect.xyz",
            "https://mobidetects.art",
            "https://mobidetects.cc",
            "https://mobidetects.info",
            "https://mobidetects.ink",
            "https://mobidetects.live",
            "https://mobidetects.pro",
            "https://mobidetects.store",
            "https://mobidetects.top",
            "https://mobidetects.xyz");

    /** The discovery path on every mirror. Joined to each base URL as-is. */
    private String checkPath = "/check.php";

    /**
     * Sent as {@code User-Agent}. The mirrors answer from an app build and gate on this string, so
     * the default mimics the app's OkHttp client; a browser UA tends to get a 403.
     */
    private String userAgent = "okhttp/4.9.2";

    /**
     * How long to wait on a single mirror (both to connect and to read) before giving up and trying
     * the next one. Short on purpose: a dead mirror should cost a second or two, not a stall, because
     * we may walk the whole pool before one answers.
     */
    private Duration timeout = Duration.ofSeconds(5);
}
