# netmirror-capture — local analysis tooling (NOT part of the Kidoo app)

This folder is **isolated dev tooling**, kept here only to study *how the capture
works*. It is deliberately outside `src/`, is never compiled by Gradle, and is
never imported or called by the Spring Boot application. Nothing here runs at
request time, serves users, or proxies content.

> **Scope / boundary.** The backend these scripts observe (NetMirror) is an
> **unlicensed** source. This tooling is for one-off, local, observational
> analysis only — it is *not* a sanctioned data source for Kidoo and must not be
> wired into application code. See "What this will NOT do" at the bottom.

---

## The one idea behind all of it

Everything here rests on a single trick: **read the traffic the browser has
already decrypted**, instead of intercepting or decrypting anything yourself.

Chrome exposes the Chrome DevTools Protocol (CDP) over a local WebSocket. Through
it you can subscribe to every request/response the browser makes — *after* TLS
has been handled by Chrome. So there is:

- **no proxy**, **no custom CA**, **no TLS interception**;
- **no device rooting**, **no APK repackaging**;
- **no anti-bot bypass** — a human solves any Cloudflare challenge and watches
  the ad; the script only *observes* the result.

That is why this approach is clean: it inspects a session a human is legitimately
driving, the same way the DevTools "Network" tab does.

---

## The files

| File | Role |
|------|------|
| `run-capture.mjs` | **The orchestrator.** Launches Chrome, drives the flow, pauses for the human ad-gate, makes the authenticated calls, saves sanitized output. Start here. |
| `capture.mjs` | **A passive recorder.** Attach it to an *already-running* Chrome (started with `--remote-debugging-port`) and it logs every request/response to `capture.jsonl`. Lower-level than the orchestrator; useful for free-form exploration. |
| `update_collection.mjs` | **A Postman patcher.** Folds already-captured, already-sanitized bodies into a Postman collection as example responses. Pure file surgery; makes no network calls. |
| `STEPS.md` | Human-readable method + the non-automated boundary. |

Requirements: **Node 18+** (uses global `fetch`/`WebSocket`, no `npm install`) and
desktop Chrome. Nothing is installed.

---

## How `run-capture.mjs` works, step by step

Run it:

```
node tools/netmirror-capture/run-capture.mjs                       # defaults
node tools/netmirror-capture/run-capture.mjs --id 81234567 --query "some title"
```

Flags: `--id` (catalog id), `--query` (search term), `--port` (CDP port, 9222),
`--gate-timeout` (ms the human step may take, default 300000), `--out` (output
dir; defaults to `./cdp-capture` next to the script — already gitignored),
`--chrome` (path to `chrome.exe`; or set `CHROME_PATH`).

**1. Launch Chrome with a throwaway profile.**
`spawn()` starts `chrome.exe` with `--remote-debugging-port=9222` and a fresh
`--user-data-dir` under the OS temp dir. The throwaway profile means the session
starts cookie-less and gets deleted at the end, so no live cookie survives on disk.

**2. Connect over CDP and arm auto-attach.**
`waitForDevtools()` polls `http://127.0.0.1:9222/json/version` until the DevTools
endpoint answers, then the tiny `CDP` class opens a WebSocket to
`webSocketDebuggerUrl`. `Target.setAutoAttach {flatten:true}` makes the browser
hand us a session for every tab/iframe automatically. For each attached target the
script enables `Network`, applies a **mobile User-Agent override** (so the site
serves its `/mobile/*` flow), enables `Page`, and releases the target with
`Runtime.runIfWaitingForDebugger`.

**3. Record traffic; keep bodies only for backend hosts.**
A single event handler listens for:
- `Network.requestWillBeSent` → remember the URL for this requestId;
- `Network.responseReceived` → note status/mime;
- `Network.loadingFinished` → *if* the host matches `BODY_HOSTS`
  (`net52.cc`, `iosmirror.cc`, `netmirror.app`, `mobiledetect.*`), fetch the body
  via `Network.getResponseBody`. Ad networks, CDNs and trackers get **metadata
  only** — their bodies are never stored.

**4. Drive the entry flow.**
- `Page.navigate` → `mobiledetect.app/check.php` (the dispatcher; its base64
  `token_hash` decodes to the web entry URL).
- `Page.navigate` → `net52.cc/mobile/verify2.php` (the shell). The server sets a
  `addhash=…::ni` cookie — the **not-verified** state — and the page JS begins
  polling the ad-gate, which returns `{"c":"n"}`.

**5. PAUSE for the human (the deliberately non-automated step).**
The script prints instructions and then **only watches**: every 3 s it evaluates
`document.cookie.includes("t_hash_t")` in the page and waits. *You* click "Click
Here", watch the real ad for >20 s, and return. When the server is satisfied it
mints a second cookie `t_hash_t=…::m` (the **verified** state) and the gate flips
to `{"c":"y"}`. The script detects the cookie's appearance — it never creates it,
never fakes the impression, never touches Cloudflare.

**6. Make the authenticated calls — with the token derived *in-page*.**
This is the key privacy move. A single `Runtime.evaluate` runs an async function
*inside the page*, where it:
- reads `document.cookie`, pulls `t_hash_t`, and derives `t` = its first `::`
  segment — **the token is computed in the browser and never handed out to the
  Node side as a reusable value**;
- calls `post.php`, `search.php`, `playlist.php`, `language.php`, `app-info.php`,
  `MyWatchList.php` with the usual `X-Requested-With: XMLHttpRequest` header;
- **redacts on the way out**: a `red()` helper replaces the token with `{{t}}` and
  any HLS `in=…::m…` signing token with `{{play_token}}` *before* the strings
  leave the page. So what returns to Node is already sanitized.

**7. Save sanitized output.**
Writes `cdp-capture/authenticated-YYYY-MM-DD.json` containing the redacted bodies
plus a note that tokens were placeholdered. No live secret is written.

**8. Clean up.**
`Browser.close`, kill Chrome, and `rmSync` the throwaway profile (which held the
only live cookies). Only the placeholdered JSON remains.

### Why re-run instead of storing a token
The session token and the HLS `in=` tokens are **short-TTL and rotate** per
rendition/session. There is no reusable key to cache — so when a session expires
you *re-run* the flow (passing the ad-gate again) rather than replaying a saved
value. That is a property of the backend, not a limitation of the script.

---

## How `capture.mjs` works

A stripped-down passive version for exploration. You start Chrome yourself:

```
chrome.exe --remote-debugging-port=9222 --user-data-dir=/tmp/nm --new-window about:blank
# get the browser ws url:
curl http://127.0.0.1:9222/json/version      # -> webSocketDebuggerUrl
node tools/netmirror-capture/capture.mjs <browserWsUrl> <outDir>
```

It arms the same auto-attach + mobile-UA override, then appends one JSON line per
event to `<outDir>/capture.jsonl` — including `requestWillBeSentExtraInfo` and
`responseReceivedExtraInfo`, which carry the raw `Cookie` / `Set-Cookie` headers
(handy for watching the `addhash` → `t_hash_t` transition). Bodies are kept only
for the same backend hosts. **`capture.jsonl` contains live cookies — treat it as
a secret and delete it after extracting what you need.**

---

## How `update_collection.mjs` works

No network at all. Run it as
`node update_collection.mjs <collection.json>` (or set `COLLECTION`; with no
argument it falls back to the original `Downloads` copy). It:
1. reads the Postman collection JSON and exits cleanly if the path doesn't exist;
2. holds a `BODIES` map of **already-sanitized** example bodies (tokens already
   `{{t}}` / `{{play_token}}`);
3. walks the collection tree, and for each request whose URL matches a known key it
   appends one example response named
   `LIVE AUTHENTICATED … (CDP browser capture, verified session)` — **idempotent**,
   it skips if that example already exists;
4. ensures two placeholder collection variables (`t`, `play_token`) exist, both
   empty and marked SECRET;
5. re-serializes and re-parses to confirm valid JSON.

---

## Using the session token in Postman (the right place for it)

The token is **never** stored in the collection or in the app. It lives only in a
local Postman environment that is never exported with the collection:

1. In Postman, **Import** `netmirror.postman_environment.json` → it adds an
   environment named **"NetMirror (local session)"** with two empty SECRET vars
   `t` and `play_token`.
2. Select that environment (top-right dropdown), then paste the values from your
   own authorized session into `t` / `play_token`. Secret-typed vars are masked and
   are **not** written back into the collection JSON.
3. Run requests. `update_collection.mjs` installs a collection-level **pre-request
   script** that copies those env values into the request scope and, if a request
   references `{{t}}`/`{{play_token}}` while the env var is empty, prints a readable
   `console.warn` in the Postman console instead of sending a literal `{{t}}`.
   Requests that need no token (e.g. the `verify2` shell) are unaffected — it warns,
   never throws.

Because the values sit in the environment (not the collection, not Spring, not a
file in git), there is nothing to redact on export and nothing to leak. When the
short-TTL token rotates, you just update the two env fields — no code anywhere
changes.

## What this will NOT do (and must stay that way)

- It does **not** fake the ad view or flip the gate programmatically.
- It does **not** solve Cloudflare's anti-bot challenge.
- It does **not** write live cookies/tokens into any kept file.
- It does **not** root a device, repackage an APK, or intercept TLS.
- It is **not** imported, called, or shipped by the Kidoo Spring Boot app, and must
  not be. It is local analysis tooling for an unlicensed source — keep it that way.
