# Authenticated NetMirror capture — method & automation

Reproduces the 2026-10-23 authenticated capture **without tampering with any device and
without bypassing anything**. It reads traffic the browser has *already decrypted* via the
Chrome DevTools Protocol (CDP) — no proxy, no custom CA, no root, no APK repackaging, no
TLS interception.

## The boundary that is deliberately NOT automated
Passing the daily ad-gate requires a **human genuinely viewing the ad for >20s**. The script
**pauses** at the gate and waits for you to do that in the visible Chrome window. It only
*observes* the gate state (polls for the verified cookie); it never fakes the impression.
Likewise it never touches Cloudflare's "Just a moment" challenge — if one appears, solve it
yourself or stop.

## Steps the automation performs
1. **Launch** the real Chrome with a throwaway profile + `--remote-debugging-port`.
2. **Attach** over CDP (auto-attach, flatten) and set a mobile UA override per session.
3. **Record** every request/response; keep response *bodies* only for the backend hosts
   (`net52.cc`, `iosmirror.cc`, `netmirror.app`, `mobiledetect.*`).
4. **Dispatcher** → GET `mobiledetect.app/check.php`, decode the base64 `token_hash`
   (= `https://net52.cc/mobile/home?app=1`) to confirm the web entry.
5. **Shell** → navigate `net52.cc/mobile/verify2.php`; the server sets `addhash=…::ni`
   (not-verified) and the page JS polls the gate → `{"c":"n"}`.
6. **PAUSE for the human** → you click "Click Here", watch the real ad >20s, return.
   The script polls (observe-only) until the server mints `t_hash_t=…::m` (verified) and
   `verify2` returns `{"c":"y"}`.
7. **Backend API coverage** → once verify2 has redirected, it settles on `mobile/home?app=1`
   (the page `TokenManager` scrapes), reads `data-time`/`data-hash` there, and calls every
   upstream endpoint the backend's `repo` classes use, in the backend's request shape:
   - browse: `post.php`, `search.php`, `episodes.php`, `/trailer.php`, `app-info.php`
   - playback: `playlist.php` (backend shape and site shape), HLS master (backend `in=t` and
     the playlist-issued URL), `p.php` heartbeat, `/v.php`, `/streamtape.php`
   - user: `recentplay.php` list, `setting.php` read, `language.php` GET
   - state-changing, each undone right after: `recentplay` add → delete, `setting` ott and hd
     toggled → restored to the values read before, `language` POSTed with its current value,
     `MyWatchList.php` `id=`. A final settings read checks they match the "before" read.
   Tokens are derived **inside the page** so they never leave the browser; HLS `in=` play
   tokens are redacted to `{{play_token}}` first (they embed `t`), then `hash` → `{{hash}}`
   and `t` → `{{t}}`. An empty result set fails the run instead of saving an empty file.
   `--series <id>` sets the episodes.php series id (defaults to `--id`).
8. **Save** sanitized bodies to `cdp-capture/authenticated-YYYY-MM-DD.json`.
9. **Clean up** → close the browser and delete the throwaway profile + raw capture (both
   hold live cookies). Only the sanitized, placeholdered output remains.

## Run it
```
cd tools/netmirror-capture
node run-capture.mjs                 # uses defaults (id 82034837, query "vishwanath")
node run-capture.mjs --id 81234567 --query "some title"
node run-capture.mjs --chrome "D:/path/chrome.exe" --out D:/somewhere   # optional overrides
```
Requires: Node 22+ (global `fetch`/`WebSocket`; the global `WebSocket` only exists from Node 21/22 on) and desktop Chrome (or `CHROME_PATH`). No npm installs.
Output goes to `cdp-capture/` next to the script, which is gitignored.

## Discovering the per-OTT home feed — `discover-ott-feed.mjs`
`run-capture.mjs` proves the *known* `/mobile/*.php` calls and only **guesses** a fixed list of
home URLs (all of which 404 / gate-shell on the live site). To find the endpoint that actually
returns a browsable per-OTT catalog, run the discovery probe instead. It stops guessing and lets
the page name its own feed:

```
cd tools/netmirror-capture
node discover-ott-feed.mjs                      # human gate, sweeps nf, pv, hs
node discover-ott-feed.mjs --otts nf            # one skin only

# FULLY AUTOMATIC — reuse a session you already earned (no window, no human):
node discover-ott-feed.mjs --headless --cookie "t_hash_t=…; cf_clearance=…"
node discover-ott-feed.mjs --headless --cookie-file session.txt
# …or let it read the backend's own env vars (same ones MirrorAccessProperties uses):
MIRROR_T_HASH_T=… MIRROR_CF_CLEARANCE=… MIRROR_BROWSER_UA="…" node discover-ott-feed.mjs --headless
```

**Automatic vs. human.** The backend never re-earns a session — `MirrorAccessProperties` replays a
`cf_clearance` (+ exact UA, same IP, ~30 min TTL) harvested once from the device WebView. This probe
does the same: give it a session you already earned (`--cookie` / `--cookie-file` / the `MIRROR_*`
env vars) and it injects the cookies, lets real Chrome clear Cloudflare with them, and runs headless
with **no human step**. The *only* thing that can't be automated is minting a brand-new `t_hash_t`
from nothing — that is the one honest ad-view (faking an impression is ad fraud), so the first
harvest, and each refresh after the ~30-min TTL lapses, still needs one real ad-view. Omit the
session args and it falls back to the human gate below (it pauses and observes; it never fakes the
impression).

Harvest the first session with the human run (`node run-capture.mjs` or this script with no
`--cookie`) or the WebView frida tool, copy the `t_hash_t`/`cf_clearance` out, and every run until
that session expires is then hands-free.

### Preflight — prove the plumbing before the real run (`--selfcheck`)
No session, no gate, no human. Launches Chrome headless, attaches CDP, evaluates, navigates to the
backend origin, and reports reachability + whether Cloudflare is blocking:
```
cd tools/netmirror-capture
npm run selfcheck            # == node run-discovery.mjs --selfcheck
```
A healthy run prints `CDP Runtime.evaluate: OK`, `reached net52.cc … readyState complete`, and
`Cloudflare challenge: not blocking`. If Chrome isn't found, pass `--chrome "<path>"` (Chrome
Beta/Canary and Edge are auto-detected as fallbacks).

### Self-healing: re-earn only when the session fails — `run-discovery.mjs`
You don't manage the session by hand. The supervisor keeps the probe automatic and asks for a human
ad-view **only at the moment the session has actually died**:

```
cd tools/netmirror-capture
node run-discovery.mjs                 # one cycle: auto if a session exists, else earn it once
node run-discovery.mjs --watch 1500    # loop every 1500s; re-earn ONLY when the session fails
node run-discovery.mjs --otts nf       # extra flags are forwarded to the probe
```

How it stays hands-free:
- It stores the live session in `cdp-capture/session.txt` (gitignored; holds real secrets, perms `600`).
- Each cycle it runs the probe headless on that saved session. Success → done, no window, no human.
- The probe exits **2** when the session is expired/rejected (distinct from a real crash, exit 1).
  On exit 2 — or when there's no session yet — the supervisor opens the **visible gate once**, you do
  the single honest ad-view, the probe saves the fresh `t_hash_t`/`cf_clearance`, and it immediately
  resumes headless. In `--watch` mode every later cycle reuses that session until it too expires.

So the human is in the loop for exactly one thing — a real ad-view when, and only when, the old
session is genuinely dead. Nothing fakes an impression and nothing bypasses Cloudflare; the session
is still IP/UA-bound, so run the supervisor from the machine that will keep serving.

Same ad-gate boundary as above when no session is supplied (it pauses and observes; it never fakes).
After the gate, for **each** OTT skin it:
1. switches the skin exactly as the site's settings UI does — `POST /mobile/setting.php` `ott=<nf|pv|hs>`;
2. reloads the real home page and records **every `/mobile/*` XHR the page fires on its own**
   while rendering that skin's rails — the catalog-shaped JSON response is the feed endpoint;
3. dumps the **rendered card DOM**: correlated `{id, poster, title}` triples, the shared
   container selector, the poster image hosts, and the rail headings — i.e. exactly the shape
   `MobileDetectService.extractPageContent` must produce (today it returns three uncorrelated
   lists, which is why the home rails are empty).

It restores the account's original skin on the way out and saves a sanitized
`cdp-capture/ott-feed-discovery-YYYY-MM-DD.json` (session token → `{{t}}`; catalog ids/titles/
posters are kept — they are the target data, not secrets). Read `sweep[].firedEndpoints` for the
feed URL and `sweep[].dom.cards` for the card shape, then wire the server extractor + client
catalog against them.

## What it will NOT do
- No faking the ad view / flipping the gate programmatically.
- No solving Cloudflare anti-bot.
- No writing live cookies/tokens to any kept file.
- No device rooting, APK repackaging, or TLS interception.
