# Tower backend API reference

The contract the Tower Android client codes against. Companion to
`android-studio-prompt.md` — paste both into a coding agent together.

There is a formatted version of this document at `docs/api-reference.html`;
this Markdown copy exists because it is what an agent parses well.

Generated from the running backend. Field names are taken from the Java DTOs,
so they match the wire exactly.

```
Base URL   http://192.168.29.122:8080
Library    E:/Entertainment
Run with   --spring.profiles.active=server
```

---

## Correction to the build prompt

The prompt says *"Jellyfin-style REST assumed"* and shows
`http://192.168.1.__:8096`. **That is the one line to change.** This server
speaks its own API on **port 8080** under `/api/media`, shaped for these
screens rather than bent to fit another product's schema. The prompt already
isolates transport in `data/remote`, so it is a one-file change on the client.

---

## Headers

| Header | When | Notes |
| --- | --- | --- |
| `Authorization: Bearer <jwt>` | Everything except `/api/health`, `/api/auth/register`, `/api/auth/login` | Register returns **201** with a token. Pass `"role":"PARENT"` to create an owner account. |
| `X-Profile-Id: <profileId>` | All profile-scoped media calls | Resume points, downloads, settings and unwatched marks are per profile, not per account. Omitted → the account's first profile. A profile id from another household returns **404**. |
| `X-Admin-Key: <key>` | `/api/media/admin/**` and the library scan | Required **in addition to** a PARENT account. Both checks, because the key alone would let a child's device act as owner if it leaked into a shared client build. Failure is **403** either way, so a response cannot confirm a guessed key. |
| `Authorization: Bearer <guest jwt>` | Watch party guests only | A different kind of token, carried the same way. It has no account behind it and reaches five endpoints for one film — everything else is **403**, including anything taking `X-Profile-Id`. See **Watch together**. |

Two endpoints are public that look like they should not be:
`POST /api/parties/{code}/guest` and its polling twin. A guest has no
credential yet, which is the whole reason they are asking for one.

## Error envelope

Every failure is the same shape, so the client needs one error path:

```json
{ "timestamp": "2026-09-10T04:12:33Z",
  "status": 410,
  "error": "Gone",
  "message": "File is no longer on disk" }
```

Two codes worth handling separately:

- **410** — the row exists but the bytes do not. The drive is unplugged, not the
  title deleted. Say "disk offline", not "not found".
- **429** — every transcode slot is busy. The message names the limit.

## Page envelope

Paged endpoints return these alongside their items, and cap `size` at **100**:

```json
{ "items": [], "page": 0, "size": 40, "totalItems": 214, "totalPages": 6 }
```

---

## The playback decision

`DIRECT PLAY` vs `WILL TRANSCODE` is decided by the **server**, not the client.
Post the device's real codec support and use whichever URL comes back.

```
POST /api/media/items/{id}/playback-decision?startSeconds=76
{ "deviceName": "Arun's Pixel",
  "videoCodecs": ["h264", "hevc"], "audioCodecs": ["aac"],
  "containers": ["mp4", "mkv"], "maxHeight": 1080,
  "maxBitrate": 20000000, "supportsHls": true }

200 { "mediaItemId": "...", "mode": "DIRECT",
      "url": "/api/media/items/.../stream?session=9f3c...",
      "sessionId": "9f3c...", "startSeconds": 76,
      "mediaInfo": { "container": "mov,mp4,m4a", "videoCodec": "h264",
                     "width": 1920, "height": 1080, "bitrate": 8000000,
                     "durationSeconds": 8880.0, "probed": true },
      "reasons": ["Direct play: container and codecs match client capabilities"] }
```

- **`mode: DIRECT`** → `GET .../stream?session=<sessionId>`. Original bytes,
  byte-range served, instant seeking, no server CPU. **Keep the `session`
  parameter** — it is what makes the stream visible in the admin panel and
  stoppable from it.
- **`mode: TRANSCODE`** → `GET /api/media/transcode/{sessionId}/index.m3u8`.
  Already serving segments by the time you request it. The stream starts at
  zero, so apply `startSeconds` client-side. Seeking past the transcoded region
  means asking for a new decision.

`reasons` is plain English on both paths — that is the sentence the playback
plan card shows.

A field left `null` in the request counts as unknown, and **every unknown
resolves toward transcoding**: wasting CPU is recoverable, a black screen is
not. A client that declares nothing is credited only with h264/aac.

`POST .../playback-decision/explain` is the same logic with no session and no
ffmpeg. It works with the disk offline, which is exactly when someone is asking
why a title will not play.

---

## Screen → endpoint map

Numbered as the build prompt numbers them. Status: **built** = done and tested,
**partial** = works but missing something the screen asks for, **none** = no
backend at all.

### 1. Home — built

```
GET /api/media/home?types=FILM,ANIME&limit=20              # the whole screen
GET /api/health                                            # TOWER · ONLINE
```

One call returns the continue-watching row, the ranked rails and the library
header. The rails it composes are still available separately, for a client that
refreshes one row on its own:

```
GET /api/media/home/popular?limit=20      # rating + views + likes, blended
GET /api/media/continue-watching?limit=20
GET /api/media/recently-added?types=FILM,ANIME&limit=20
GET /api/media/recently-added?types=PHOTO,HOME_VIDEO      # camera roll rail
GET /api/media/library-summary
```

### 2. Library — built

```
GET /api/media/items?category=ours&unwatched=true&minHeight=2160&sort=title&page=0
GET /api/media/library-summary                             # count + total size
```

`category` accepts the chip labels directly: `all`, `films`, `anime`, `ours`,
`music`, `photos`. `minHeight=2160` is the `4K ONLY` chip. `sort` is one of
`title` `added` `captured` `year` `rating` `likes`.

There is no `sort=views`: a play count is the sum of two columns and a JPA
`Sort` cannot express that. Most-watched ordering is the home screen's
`most-watched` rail, which sorts on the sum in JPQL.

### 3. Search — partial

```
GET /api/media/items?q=inception     # matches title, sort title and filename
GET /api/media/people
```

Missing: no `NEEDS TRANSCODE` hint on results (that needs device capabilities
at browse time, which only the decision endpoint takes today), and no folder
"Jump to" facet.

### 4. Movie detail — partial

```
GET  /api/media/items/{id}
GET  /api/media/items/{id}/backdrop
POST /api/media/items/{id}/playback-decision    # the playback plan card
```

Missing: "Also on the disk" has no related-items endpoint. The cast button has
no backend — see screen 11.

### 5. Player, portrait — built

```
GET /api/media/items/{id}/player-state    # one call restores everything
PUT /api/media/items/{id}/progress
PUT /api/media/items/{id}/tracks
```

Post progress every few seconds and on pause/stop. It upserts, so repeats are
free — and the increments are what the admin watch-hours chart is built from.

### 6. Player, landscape — built

Gestures and chrome are entirely client-side. Same endpoints as portrait.

### 7. Thumbnail scrubbing — built

```
GET    /api/media/items/{id}/trickplay
POST   /api/media/items/{id}/trickplay              # 202 GENERATING, poll it
GET    /api/media/items/{id}/trickplay/sheet_0003.jpg
DELETE /api/media/items/{id}/trickplay
```

**404 until generated** — fall back to a plain scrubber rather than treating it
as an error.

### 8. Downloads / Saved — partial

```
POST   /api/media/items/{id}/download    # 200 READY (passthrough) | 202 queued
GET    /api/media/downloads?page=0&size=20
GET    /api/media/downloads/{jobId}      # poll for percent
GET    /api/media/downloads/{jobId}/file # Range-capable, resumable
DELETE /api/media/downloads/{jobId}
PUT    /api/media/settings               # "play saved copies only"
```

Missing: free space is the phone's, so the client supplies it. The
category-coloured usage bar needs each job's media type, which
`DownloadJobDto` does not carry yet.

### 9. Connect server — none

No mDNS/SSDP advertisement, no pairing handshake, no QR payload. The manual-IP
path works today (`http://192.168.29.122:8080` plus register or login), so
build that field first and treat auto-discovery as a later screen.

### 10. Profiles & settings — partial

```
GET  /api/profiles
GET  /api/media/settings
PUT  /api/media/settings
GET  /api/media/library/status
POST /api/media/library/scan             # admin key
GET  /api/media/admin/matches            # "Fix wrong matches · N titles"
```

`showTechnicalBadges` is stored server-side so it follows a person between
devices. Missing: preferred subtitle language, and separate Wi-Fi vs mobile
quality — there is one `awayMaxHeight` today, not a pair.

### 11. Cast to TV — none

No device registry and no per-device capability store, so
`DIRECT PLAY · 4K CAPABLE` cannot be computed for anything but the phone making
the request. The decision engine would serve this unchanged once devices can be
registered.

### 12. Fix wrong metadata — built

```
GET  /api/media/admin/matches?page=0
GET  /api/media/admin/matches/{id}?q=arrival
PUT  /api/media/admin/matches/{id}
POST /api/media/admin/matches/{id}/reclassify
POST /api/media/admin/matches/{id}/unmatch
POST /api/media/admin/matches/{id}/reset
```

The screen's promise is served from the backend as `reassurance`, so client
copy cannot drift from what the server does. `remaining` is the
`N MORE MISMATCHES QUEUED` line. This is the **only** endpoint that returns real
filesystem paths, because identifying a file is what it asks the owner to do.

### 13. Home-video timeline — partial

```
GET /api/media/timeline?groupBy=date&page=0&size=60    # date | person | place
GET /api/media/people
```

Read-only. `undatedCount` powers "9 clips have no date — tag them?" but there is
**no endpoint to do the tagging**: capture date, place and people cannot be
written yet. Groups span pages; merge them client-side on `key`.

### 14. Degraded states — partial

```
GET /api/media/reachability
GET /api/media/items/{id}/playback-cost?height=720
```

The server answers "am I at home?" by comparing your address against its own
interface prefixes — the client cannot, since Android reports Wi-Fi without
knowing whose. Missing: no sleep detection and no wake-on-LAN, so
`TOWER · SLEEPING` and "Wake the server" have nothing behind them.

### 15. Phone → TV handoff — none

Depends on screen 11. Session transfer and phone-as-remote need a device
registry first.

### 16. Watch together — built

One host, up to `maxMembers` devices, one clock. The host's player is the
authority and everyone else follows it over a WebSocket — the push channel this
section used to say the stack was missing. Cast and handoff can reuse it.

Friends with an account on this server join with the code. Friends without one
knock, the host taps accept, and they get a token good for that single title
while the party lasts: no library, no resume point, no other film.

```
POST /api/parties                      # host opens one, gets a code
POST /api/parties/{code}/join          # account holder
POST /api/parties/{code}/guest         # no account — public
POST /api/parties/{code}/admit         # host says yes or no
WS   /ws/party?code=…&token=…
```

Full shape, the frame protocol and the drift-correction the client owes are all
in **Watch together** under the endpoint reference.

### 17. Requests — none

Nothing built, and the cheapest remaining feature: a request row, a status, an
owner inbox. No streaming, no realtime, no ffmpeg.

### 18. Subtitles — partial

```
GET /api/media/items/{id}/subtitles/{index}    # converted to WebVTT
PUT /api/media/items/{id}/subtitle-offset
```

Embedded tracks are *listed* in item detail but not servable — requesting one
returns **501**, since extraction needs an ffmpeg pass that is not wired up. No
line counts, and no online search by file hash.

### 19. Kids mode — partial

Profiles exist. The two things that make it kids mode do not: no approved-title
list, so the 2×2 grid has nothing to filter by, and no daily time limit, so
`45 MIN LEFT TODAY` has nothing to count.

### 20. Admin panel — built

```
GET    /api/media/admin/health
GET    /api/media/admin/people
GET    /api/media/admin/sessions
DELETE /api/media/admin/sessions/{sessionId}
GET    /api/media/admin/disk?biggestFiles=20
DELETE /api/media/admin/disk/caches
```

`watchWeek` arrives with the peak day already flagged, so the chart does not
have to scan for which bar to paint solid. `needsALook` suppresses every
zero-count row — an empty list means a healthy server, not a missing feature.

---

## Endpoint reference

### Catalog

**`GET /api/media/items`** — paged browse and search.
Query: `category` `q` `genre` `unwatched` `minHeight` `sort` `page` `size`.
→ `{ items[ItemSummaryDto], page, size, totalItems, totalPages }`

`ItemSummaryDto`:
```
id, type, title, year, runtimeMinutes, rating, quality, genres[],
hasPoster, hasBackdrop, missing, resumePositionSeconds, watched,
percentComplete, capturedAt, artist, album,
viewCount, likeCount, liked
```

The last three are the ranking signals, carried on every tile so a grid can draw
its heart and its "played 12 times" label without a call per poster. `viewCount`
is plays started, household-wide; `likeCount` is the household total; `liked` is
whether the profile in `X-Profile-Id` likes it.

**`GET /api/media/items/{id}`** → `ItemDetailDto`. **410** if the file is gone.
```
id, type, libraryName, title, originalTitle, year, plot, tagline,
runtimeMinutes, rating, certification, genres[], directors, castMembers,
studio, quality, tmdbId, imdbId, artist, album, trackNumber,
capturedAt, place, people[], fileSize, fileName, hasPoster, hasBackdrop,
mediaInfo, subtitles[], audioTracks[], resumePositionSeconds, watched,
viewCount, likeCount, liked
```

`mediaInfo`: `container, durationSeconds, videoCodec, width, height, bitrate,
audioCodecs, audioChannels, probed`

`subtitles[]`: `index, language, format, forced, hearingImpaired, embedded`

`audioTracks[]`: `index, codec, language, title`

**`GET /api/media/recently-added`** — a rail, capped at 50, unpaged by design.
Query: `types` (comma-separated), `limit`. → `ItemSummaryDto[]`

### Home screen

**`GET /api/media/home`** — the whole screen in one call.
Query: `types` (comma-separated, default `FILM,ANIME`), `limit` (posters per
rail, capped at 50).

```json
{ "continueWatching": [ { "item": {}, "positionSeconds": 0,
                          "durationSeconds": 0, "percentComplete": 0 } ],
  "rails": [ { "key": "popular", "title": "Popular in your library",
               "rankedBy": "popularity",
               "items": [ { "item": {}, "score": 0.904,
                            "reason": "Played 30 times" } ] } ],
  "library": {},
  "weights": { "rating": 0.4, "views": 0.35, "likes": 0.25 },
  "generatedAt": "2026-09-10T05:09:11Z" }
```

Rails come back in render order and an empty rail is omitted rather than sent as
a bare heading, so a fresh library returns `"rails": []`:

| `key` | `rankedBy` | Order |
| --- | --- | --- |
| `popular` | `popularity` | The blend of all three signals |
| `top-rated` | `rating` | `rating` desc; unrated titles are excluded |
| `most-watched` | `views` | plays desc, direct and transcoded together |
| `most-liked` | `likes` | `likeCount` desc |
| `recently-added` | `added` | `addedAt` desc |

`score` is 0–1 and is present only on `popular`; elsewhere it is null, because a
single-signal rail's position is already its own number. `reason` is a subtitle
naming why the title is there — `Rated 8.4`, `Played 30 times`, `Liked by 3 in
your house`.

**How `popular` is ranked.** Each signal is normalised to 0–1 and then weighted
`0.40` rating, `0.35` views, `0.25` likes. Rating leads because it is the only
signal that exists before anyone has watched anything. Play and like counts are
normalised on a log curve against the library's own maximum — linear scaling
would let one endlessly-rewatched favourite push every other title to zero and
collapse the rail into a rating sort. An unrated title is scored at the
library's mean rating, not zero, so home footage the scraper never matched can
still surface. Candidates are the union of the top 100 by each signal.

**`GET /api/media/home/popular`** — the blended rail alone, same query
parameters. → one rail object.

### Likes

Per profile, so each member of a household likes for themselves; the count is
the household's. Both write verbs are idempotent, so a retry on a flaky
connection cannot double-count or toggle back.

**`PUT /api/media/items/{id}/like`**, **`DELETE`**, **`GET`** →
`{ mediaItemId, liked, likeCount }`. **404** if the item is unknown. Unlike
browsing, an item whose file is currently missing still accepts a like — the
opinion is about the title, and an unplugged disk should not lose it.

**`GET /api/media/library-summary`** →
`{ itemCount, totalBytes, categories[{ type, label, itemCount, totalBytes }], genres[] }`

**`GET /api/media/timeline`** — query `groupBy` `page` `size` →
`{ groupBy, groups[{ key, label, itemCount, items[] }], undatedCount, page, size, totalItems, totalPages, hasMore }`

**`GET /api/media/genres`**, **`GET /api/media/people`** → `String[]`.
Bounded by the domain, so neither is paged.

**`GET /api/media/items/{id}/poster`**, **`/backdrop`** → image bytes, cached a
day. **404** when the item has none.

### Playback

**`POST /api/media/items/{id}/playback-decision`** — see the section above.
Query `startSeconds`. **429** slots busy, **415** needs transcoding but the
client declared no HLS.

**`POST .../playback-decision/explain`** → `String[]` of reasons. No session
started, no counter moved, works disk-offline.

**`GET /api/media/items/{id}/stream`** — query `session`. Full Range support:
correct 206 and 416, which is what makes seeking work. **403** if the owner
ended that session.

**`GET /api/media/transcode/{sessionId}/index.m3u8`** — growing playlist, never
cached. Segment names resolve relative to it, so no rewriting is needed.

**`GET /api/media/transcode/{sessionId}/seg_00001.ts`** — a **404** means ffmpeg
has not produced it yet. Retry rather than fail.

**`DELETE /api/media/transcode/{sessionId}`** → 204. Frees a slot immediately;
the reaper handles clients that vanish.

**`GET /api/media/items/{id}/media-info`** → `MediaInfoDto`, for a debug screen.

### Watch together

Several devices on one film. The party is persisted; the playhead is not, so a
restart ends every party — which is honest, because the sockets holding them
together are gone too.

**`POST /api/parties`** — body `{ mediaItemId, maxMembers?, requireApprovalForGuests? }`
→ **201** `PartyDto`. Opening a second party retires the first — tapping "watch
together" again means the first attempt reached nobody.

`maxMembers` is capped at 8 and defaults to `app.parties.max-members`, **4** on
this server. That number is upstream bandwidth, not preference:

```
40 Mbps up, less headroom for the rest of the house    ~32 Mbps usable
a 1080p title in this library                             8 Mbps
                                                        4 streams
```

So four total with a host watching from elsewhere, or three friends plus a host
on the LAN, who costs no upstream at all. A library of high-bitrate remuxes
(nearer 20 Mbps) fits two. Change the property, not the client.

```
partyId, code, mediaItemId, itemTitle, live, maxMembers, youAreHost,
members[{ id, name, role, host, joinedAt, online, buffering, driftSeconds }],
pending[{ requestId, name, requestedAt }],
clock{ state, positionSeconds, atEpochMillis, serverNowEpochMillis },
capacityWarning
```

`role` is `HOST` `MEMBER` `GUEST`. `online` is not the same as being in the
party — someone in a tunnel keeps their seat. `pending` is empty for everyone
but the host. `clock` is null until the host's player reports a position.
`capacityWarning` is set when the title has never once direct-played. For those,
CPU binds long before bandwidth does — `app.media.max-transcode-sessions` is
**2** here, and a decision with no slot free is a **429** rather than a queue,
so a party of four on such a title will see two of its seats refused. Advisory
only: the party opens anyway, because the host is the one who knows what their
machine can take.

The six-character code uses an alphabet with `0 O 1 I L` removed, because it
gets read across a room and retyped on a TV remote. Lower case and pasted
spaces or hyphens are accepted.

**`POST /api/parties/{code}/join`** → `PartyDto`. Account holders, no approval:
they can already stream any title, so gating the rendezvous would protect
nothing. Safe to repeat — a reconnect calls it again and reuses the same seat.
**409** every seat taken. **404** unknown code *or* ended party: the two are
deliberately indistinguishable, so guessing cannot confirm a real code.

**`GET /api/parties/{code}/state`** → `PartyDto`. The polling fallback for a
client that cannot hold a socket open — the same shape the socket pushes, just
later. **403** for a non-member.

**`POST /api/parties/{code}/leave`** → 204. The host leaving ends the party for
everyone. There is no handover: the host's player *is* the clock, so promoting
someone would mean adopting a different device's playhead mid-film.

**`DELETE /api/parties/{code}`** → 204, host only. Everyone gets an `ended`
frame before their socket closes.

#### Guests — no account on this server

**`POST /api/parties/{code}/guest`** — **public** — body `{ name }` →
`{ status, requestId, pollToken, token, expiresAt }`. Normally
`status: "PENDING"` with `token: null`: holding a code is weak evidence of an
invitation, since codes get forwarded and read over shoulders. Rate limited per
address — **429** when someone is guessing codes. The name is stripped of
control characters and capped at 40, so an accept prompt cannot be dressed up
to read as something else.

**`GET /api/parties/{code}/guest/{requestId}?token=<pollToken>`** — **public** —
same shape. `PENDING` until the host answers, then `ADMITTED` with a token, or
`DENIED`. Unanswered requests expire after **5 minutes**. `pollToken` is
returned once, on the first response, and stays valid for the life of the party
so an app that restarts can fetch its token again rather than making the host
approve the same person twice. It is separate from `requestId` because the
request id reaches the host in the accept prompt and so is not a secret.

**`POST /api/parties/{code}/admit`** — host only — body `{ requestId, allow }`
→ `PartyDto`. **409** if that request was already answered, by a double tap or
by the timeout.

**`POST /api/media/guest/playback-decision`** — guest token — query
`startSeconds`, body `ClientCapabilitiesRequest` → the same
`PlaybackDecisionDto` an account gets. No item in the path: the title is in the
token, put there when the host admitted them. Everything after this is the
shared path — the returned `/stream` or playlist URL behaves identically.

A guest token reaches exactly this and nothing else:

```
POST /api/media/guest/playback-decision
GET  /api/media/items/{id}/stream           # {id} must be the party's title
GET  /api/media/items/{id}/subtitles/{n}    # and /poster, /backdrop
GET  /api/media/transcode/{sessionId}/**
WS   /ws/party
```

Everything else is **403**, including every endpoint that takes `X-Profile-Id`
— a guest has no profile for one to name. The **party**, not the token's
expiry, is the authority: ending it makes a token with hours left worthless and
stops that guest's stream at once. Members with accounts are not cut off, since
they could have played the title without any party at all.

#### The socket

```
ws://host/ws/party?code=K7M2QP&token=<account or guest jwt>
```

The token is in the query because a browser cannot set a header on a WebSocket.
A refused handshake is **403** and never becomes a socket. A second connection
from the same person replaces the first.

Server → client:

```json
{"type":"tick","clock":{ … },"by":null}
{"type":"play","clock":{ … },"by":"Amma"}
{"type":"pause","clock":{ … },"by":"Amma"}
{"type":"seek","clock":{ … },"by":"Amma"}
{"type":"members","members":[ … ]}
{"type":"pending","guest":{"requestId":"…","name":"Ravi","requestedAt":"…"}}
{"type":"ended","reason":"host ended it"}
{"type":"error","message":"only the host controls playback"}
```

`tick` every 2s and once on connect; the rest the moment they happen. The
payload of a `pause` is identical to a `tick` — the type exists so the client
can say "Amma paused" rather than moving the bar silently. `pending` goes to
the host alone.

Client → server:

```json
{"type":"play"|"pause"|"seek","positionSeconds":812.4}
{"type":"report","positionSeconds":812.1,"buffering":false}
```

The first three are host-only; a member sending one gets an `error` frame and
keeps its socket. `report` is for everyone, every few seconds: from the host it
re-anchors the party to their real position, from anyone else it only records
how far off they are, which is what `driftSeconds` in the member list shows.

#### Following the clock — the client's half

The clock is an anchor, not a counter. `positionSeconds` was true at
`atEpochMillis` and nothing advances it, so a slow tick, a missed tick and a
reconnect mid-film all land on the same answer. Both timestamps are the
server's, so a device with a badly set clock cannot drag the party off.

```
offset = serverNowEpochMillis - localNow        // re-estimate on every frame
target = positionSeconds + (localNow + offset - atEpochMillis) / 1000
drift  = target - myPosition                    // only while state is PLAYING
```

| `drift` | what to do |
| --- | --- |
| over 2.0s | seek to `target` |
| 0.35 – 2.0s | `playbackRate` 1.06 or 0.94 until under 0.1s, then back to 1.0 |
| under 0.35s | nothing |

The rate nudge is the part that matters: a seek every few seconds is visible
and ugly, a 6% rate change for two seconds is not.

Two behaviours worth building the UI around: the host dropping **pauses** the
party rather than ending it, so a tunnel or a locked phone does not end the
evening, and a party with nobody connected for 15 minutes is closed.

### Player state

**`GET /api/media/items/{id}/player-state`** — one round trip instead of four.
```
mediaItemId, positionSeconds, durationSeconds, watched,
subtitleOffsetSeconds, subtitleTrackIndex, audioTrackIndex,
chapters[{ index, startSeconds, endSeconds, title }],
trickplay{ state, intervalSeconds, tileWidth, tileHeight, columns, rows,
           framesPerSheet, frameCount, sheetCount, sheetUrlTemplate, error }
```

**`PUT /api/media/items/{id}/progress`** —
body `{ positionSeconds, durationSeconds, finished }` →
`{ mediaItemId, positionSeconds, durationSeconds, watched, percentComplete,
   subtitleOffsetSeconds, subtitleTrackIndex, audioTrackIndex, updatedAt }`

Past 95% of the runtime counts as finished — films end with credits nobody
watches.

**`PUT .../subtitle-offset`** — body `{ offsetSeconds }`.
**`PUT .../tracks`** — body `{ subtitleTrackIndex, audioTrackIndex }`.

**`GET /api/media/continue-watching`** — query `limit` →
`[{ item: ItemSummaryDto, positionSeconds, durationSeconds, percentComplete }]`

Under 30 seconds is not remembered, so a mis-tap does not litter the row.

**`GET /api/media/items/{id}/subtitles/{index}`** → `text/vtt`, with a
Windows-1252 fallback for legacy files. **501** for an embedded track.

### Offline and away

**`POST /api/media/items/{id}/download`** — body `{ height, capabilities }`.
**200** READY when the original already suits the device (passthrough — no CPU,
no wait, no second generation of compression), **202** when queued for
conversion.

**`GET /api/media/downloads`** — query `page` `size` →
`{ items[], page, size, totalItems, totalPages, readyCount, inProgressCount, readyBytes }`

Totals count the whole list, not the page.

**`GET /api/media/downloads/{jobId}`** →
```
jobId, mediaItemId, itemTitle,
state (QUEUED | CONVERTING | READY | FAILED | CANCELLED | EXPIRED),
mode (PASSTHROUGH | CONVERT), percent, sourceHeight, targetHeight,
bytes, ready, fileUrl, label, error, createdAt, expiresAt
```

`label` is the ready-made mono line, e.g. `CONVERTING 4K -> 1080P · 68%`, built
server-side so wording and percentage cannot disagree.

**`GET /api/media/downloads/{jobId}/file`** — Range-capable, so an interrupted
download resumes rather than restarting. Sent as an attachment named from the
title. **409** not ready, **410** prepared copy expired.

**`DELETE /api/media/downloads/{jobId}`** — cancels a conversion (killing
ffmpeg) or discards a prepared copy.

**`GET /api/media/reachability`** →
`{ atHome, location (HOME|AWAY), clientAddress, explanation, uploadBitsPerSecond, suggestedHeight }`

**`GET /api/media/items/{id}/playback-cost`** — query `height` →
`{ mediaItemId, atHome, originalBytes, transcodedBytes, transcodeHeight, uploadBitsPerSecond, originalFitsUpload, explanation }`

**`GET /api/media/settings`**, **`PUT /api/media/settings`** →
`{ profileId, awayBehaviour (ASK|SAVED_ONLY|STREAM), downloadHeight, awayMaxHeight, showTechnicalBadges }`

PUT takes only the fields that changed.

### Owner only — admin key **and** PARENT

**`GET /api/media/admin/health`** →
```
startedAt, uptimeSeconds, activeStreams, profileCount, totalMegabitsPerSecond,
transcodeLoad{ activeTranscodes, maxTranscodes, atCapacity, sessions[] },
watchWeek[{ date, label, hours, peak }], watchWeekTotalHours,
needsALook[{ kind, headline, detail, count, severity, action }],
itemCount, libraryBytes, librariesConfigured, ffmpegConfigured
```

`needsALook.kind` is one of `WRONG_MATCHES` `ALWAYS_TRANSCODES` `MISSING_FILES`
`UNDATED_CLIPS` `DISK_SPACE`; `severity` is `INFO` `WARN` `CRITICAL`.

**`GET /api/media/admin/people`** →
```
liveSessions[], usageThisMonth[{ profileId, profileName, hoursThisMonth,
sharePercent, habitLine }], totalHoursThisMonth, peakConcurrentStreams,
suggestion | null
```

`suggestion` is null until the server has evidence for one.

**`GET /api/media/admin/sessions`** → `SessionDto[]`
```
sessionId, profileId, profileName, deviceName, mediaItemId, itemTitle,
mode, targetHeight, positionSeconds, durationSeconds, percentComplete,
megabitsPerSecond, bytesServed, startedAt, idleSeconds, terminated
```

**`DELETE /api/media/admin/sessions/{sessionId}`** → 204. A transcode dies at
once; a direct play stops on its next range request, which for a player pulling
every few seconds is effectively immediate.

**`GET /api/media/admin/disk`** — query `biggestFiles` →
```
categories[{ type, label, itemCount, bytes, percentOfLibrary }],
volumes[{ path, store, totalBytes, usableBytes, usedBytes, percentUsed }],
biggestFiles[{ mediaItemId, title, type, fileName, fileSize, quality,
               alwaysTranscodes, neverWatched, playCount, note }],
reclaimable{ transcodeCacheBytes, trickplayCacheBytes, downloadCacheBytes,
             watchedByEveryoneBytes, watchedByEveryoneCount, totalBytes }
```

**`DELETE /api/media/admin/disk/caches`** → `{ freedBytes, detail }`. Deletes
only scratch caches, which regenerate. **No endpoint anywhere can delete a
media file.**

**`GET /api/media/admin/matches`** — query `page` `size` →
`{ items[MismatchDto], page, size, totalRemaining, totalPages }`

**`GET /api/media/admin/matches/{id}`** — query `q` `limit` →
```
item{ mediaItemId, type, currentTitle, currentYear, fileName, folderPath,
      metadataSource, fileSize, hasPoster },
candidates[{ id, title, year, plot, runtimeMinutes, rating, certification,
             studio, directors, genres[], tmdbId, imdbId,
             origin, reason, matchPercent }],
candidateSource, reassurance, remaining
```

`origin` is `sidecar` `folder` `filename` `library` or `typed`, so the client can
weigh a scraped record differently from a guess.

**`PUT /api/media/admin/matches/{id}`** — body `{ candidateId }` or
`{ title, year }` → `FixResultDto`. **409** for a stale `candidateId` — reload
the candidates and choose again. The correction is locked as `MANUAL`, so a
rescan cannot undo it. Nothing on disk is renamed.

**`POST .../reclassify`** — body `{ "type": "ours" }`. Locks the type too.
**`POST .../unmatch`** — hides it, keeping the row and the file.
**`POST .../reset`** — undoes a correction, letting the next scan re-read it.

**`POST /api/media/library/scan`** → 202 `ScanStatusDto`, **409** if a scan is
running. **`GET /api/media/library/status`** →
```
running, startedAt, finishedAt, currentFile, error, filesSeen, added,
updated, unchanged, markedMissing, failed, moviesInLibrary, rootsConfigured
```

---

## Thumbnail scrubbing arithmetic

The manifest lets the client crop preview frames locally, which is what makes
dragging the scrubber cost no network round trips. The six-frame filmstrip
usually comes from a sheet already in memory.

```
frame        = floor(seconds / intervalSeconds)
sheetIndex   = frame / framesPerSheet
indexInSheet = frame % framesPerSheet
column       = indexInSheet % columns
row          = indexInSheet / columns

sheet URL    = sheetUrlTemplate, {sheet} = zero-padded 4-digit sheetIndex
crop         = (column * tileWidth, row * tileHeight, tileWidth, tileHeight)
```

`tileHeight` is `0` when the file was never probed — read it from the first
sheet you load instead.

---

## First run

1. **Account and profile.** `POST /api/auth/register` with `role: "PARENT"`,
   then `POST /api/profiles` per family member. Keep the profile ids.
2. **Index the disk.** `POST /api/media/library/scan` with the admin key, then
   poll `/api/media/library/status`. The first scan runs ffprobe per file, so it
   takes a while.
3. **Check the guesses.** `GET /api/media/admin/matches` shows what the filename
   parser got wrong. On this library that was 24 titles — worth fixing before
   anyone browses. See `scripts/matchfix-corrections.py`.
4. **Confirm ffmpeg.** Run `ffprobe -version` on the server first. Without it
   every title reports "not probed" and falls back to transcoding — and
   transcoding will not work either.

## Not yet proven against real media

ffmpeg transcoding, download conversion and sprite generation are all tested up
to the point of invoking the binary, using synthetic fixtures. The first real
scan of `E:/Entertainment` is what will confirm them.

Watch parties are tested end to end — real WebSocket connections, two accounts,
guest tokens, real files on disk — but never yet with several real devices on
real networks. Two things only that will tell you: whether the drift thresholds
above hold on a phone over wifi, and whether four concurrent streams really fit
in 40 Mbps once the rest of the household is using it. The second is one
property, `app.parties.max-members`, not a rebuild.
