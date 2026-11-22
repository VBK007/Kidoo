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
7. **Authenticated calls** → once verify2 has redirected, it settles on `mobile/home` and calls
   `post.php`, `search.php`, `playlist.php`, `language.php`, `app-info.php` and
   `MyWatchList.php` (the HLS master and `trailer.php` are not fetched). The session token
   (`t`) is derived **inside the page** so it never leaves the browser; HLS `in=` play tokens
   are redacted to `{{play_token}}` first (they embed `t`), then `t` itself to `{{t}}`.
   An empty result set fails the run instead of saving an empty file.
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
Requires: Node 18+ (global `fetch`/`WebSocket`) and desktop Chrome (or `CHROME_PATH`). No npm installs.
Output goes to `cdp-capture/` next to the script, which is gitignored.

## What it will NOT do
- No faking the ad view / flipping the gate programmatically.
- No solving Cloudflare anti-bot.
- No writing live cookies/tokens to any kept file.
- No device rooting, APK repackaging, or TLS interception.
