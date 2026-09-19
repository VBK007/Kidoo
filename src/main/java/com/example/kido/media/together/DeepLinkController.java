package com.example.kido.media.together;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.profile.Profile;
import com.example.kido.profile.ProfileRepository;

/**
 * The public, unauthenticated half of a watch-party invite: what a browser or an
 * OS-level link handler sees when someone taps a shared {@code /join/{code}} URL.
 *
 * <p>Two audiences, one path. When the Tower app is installed and Android App Links /
 * iOS Universal Links have been verified (the two {@code .well-known} endpoints below
 * exist to make that verification possible), the OS intercepts the link before it ever
 * reaches a browser and opens the app directly — this controller never runs. This
 * controller is what everyone else sees: a browser with no app installed, or a phone
 * where verification has not been set up yet, which is why every reply here is a
 * self-contained web page, not JSON — there is no client on the other end that parses
 * responses.
 *
 * <p>Both {@code .well-known} files answer 404 while their identifiers are
 * unconfigured, deliberately, rather than serving a JSON document with empty/placeholder
 * values: an unverifiable stub is worse than no file at all, since the OS would attempt
 * and fail verification instead of simply not trying.
 */
@RestController
public class DeepLinkController {

    private final WatchPartyRepository parties;
    private final MediaItemRepository items;
    private final ProfileRepository profiles;

    private final String customScheme;
    private final String androidPackage;
    private final List<String> androidSha256Fingerprints;
    private final String iosTeamId;
    private final String iosBundleId;

    public DeepLinkController(WatchPartyRepository parties,
                              MediaItemRepository items,
                              ProfileRepository profiles,
                              @Value("${app.deeplink.custom-scheme:tower}") String customScheme,
                              @Value("${app.deeplink.android-package:}") String androidPackage,
                              @Value("${app.deeplink.android-sha256-fingerprints:}") String androidSha256,
                              @Value("${app.deeplink.ios-team-id:}") String iosTeamId,
                              @Value("${app.deeplink.ios-bundle-id:}") String iosBundleId) {
        this.parties = parties;
        this.items = items;
        this.profiles = profiles;
        this.customScheme = customScheme;
        this.androidPackage = androidPackage;
        this.androidSha256Fingerprints = androidSha256.isBlank()
                ? List.of()
                : List.of(androidSha256.split("\\s*,\\s*"));
        this.iosTeamId = iosTeamId;
        this.iosBundleId = iosBundleId;
    }

    /**
     * The web fallback for a shared watch-party link. Renders the invite (or an
     * "expired" state for an unknown/ended code) with a button that tries the app via
     * both the verified web link and the custom scheme, and always shows the raw code
     * as the ultimate fallback — the app's own manual-join screen needs nothing else.
     */
    @GetMapping(value = "/join/{code}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> join(@PathVariable String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        return parties.findByJoinCodeAndEndedAtIsNull(normalized)
                .map(party -> ResponseEntity.ok(renderInvite(party, normalized)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(renderExpired()));
    }

    /**
     * Android App Links verification. 404 (no association at all) until an Android
     * package and at least one signing certificate fingerprint are configured.
     */
    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> androidAssetLinks() {
        if (androidPackage.isBlank() || androidSha256Fingerprints.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String fingerprints = androidSha256Fingerprints.stream()
                .map(fp -> "\"" + fp + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        String body = "[{\"relation\":[\"delegate_permission/common.handle_all_urls\"],"
                + "\"target\":{\"namespace\":\"android_app\","
                + "\"package_name\":\"" + androidPackage + "\","
                + "\"sha256_cert_fingerprints\":[" + fingerprints + "]}}]";
        return ResponseEntity.ok(body);
    }

    /**
     * iOS Universal Links verification. 404 until a Team ID and Bundle ID are
     * configured. {@code appIDs} is deliberately the only key set — this server has no
     * other associated-domains capability (webcredentials, appclips) to declare.
     */
    @GetMapping(value = "/.well-known/apple-app-site-association", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> appleAppSiteAssociation() {
        if (iosTeamId.isBlank() || iosBundleId.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        String appId = iosTeamId + "." + iosBundleId;
        String body = "{\"applinks\":{\"apps\":[],\"details\":[{\"appID\":\"" + appId + "\","
                + "\"paths\":[\"/join/*\"]}]}}";
        return ResponseEntity.ok(body);
    }

    private String renderInvite(WatchParty party, String code) {
        MediaItem item = items.findById(party.getMediaItemId()).orElse(null);
        Profile host = profiles.findById(party.getHostProfileId()).orElse(null);
        String title = escape(item == null ? "a movie" : item.getTitle());
        String hostLine = host == null ? "" : "<p class=\"host\">" + escape(host.getName()) + " invited you</p>";
        String webLink = "/join/" + code;
        String appLink = customScheme + "://join/" + code;

        return page("Watch together", """
                <div class="card">
                  <p class="eyebrow">Watch party</p>
                  <h1>%s</h1>
                  %s
                  <a class="button" href="%s" onclick="location.href='%s'">Open in Tower</a>
                  <p class="code-label">Or enter this code in the app</p>
                  <p class="code">%s</p>
                </div>
                """.formatted(title, hostLine, appLink, webLink, code));
    }

    private String renderExpired() {
        return page("Invite not found", """
                <div class="card">
                  <p class="eyebrow">Watch party</p>
                  <h1>This invite isn't active</h1>
                  <p class="host">The party may have ended, or the link is incomplete.</p>
                </div>
                """);
    }

    private static String page(String title, String bodyHtml) {
        String html = """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <style>
                  :root { color-scheme: light dark; }
                  body { margin:0; min-height:100vh; display:flex; align-items:center;
                         justify-content:center; background:#0b0b10; color:#f2f2f5;
                         font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif; }
                  .card { max-width:360px; padding:32px 28px; text-align:center; }
                  .eyebrow { margin:0 0 8px; font-size:13px; letter-spacing:.08em;
                             text-transform:uppercase; color:#9a9aa5; }
                  h1 { margin:0 0 12px; font-size:24px; line-height:1.3; }
                  .host { margin:0 0 24px; color:#c4c4cc; font-size:15px; }
                  .button { display:inline-block; padding:14px 28px; border-radius:999px;
                            background:#f2f2f5; color:#0b0b10; text-decoration:none;
                            font-weight:600; font-size:16px; }
                  .code-label { margin:28px 0 6px; font-size:13px; color:#9a9aa5; }
                  .code { margin:0; font-size:28px; font-weight:700; letter-spacing:.12em; }
                </style>
                </head>
                <body>
                %s
                </body>
                </html>
                """.formatted(title, bodyHtml);
        return html;
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
