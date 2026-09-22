# Kido

A Spring Boot backend that does two jobs for one household.

**Tower** — a home media server. It indexes the movie drive, decides per device
whether a file can be played as-is or has to be transcoded, streams it, keeps
everyone's resume point, and lets several people watch the same film in sync.

**Kiduu** — the kids' learning app behind it: profiles per family member,
published content, screen-time limits, a parent PIN, subscriptions and ads.

One service, one database, one auth model. The media module stays dormant if no
library path is configured, so the app is useful with or without it.

- **API contract:** [`docs/api-reference.md`](docs/api-reference.md) — every
  endpoint, field by field, generated from the Java DTOs. Also as
  [HTML](docs/api-reference.html).
- **Load testing:** [`scripts/LOAD-TESTING.md`](scripts/LOAD-TESTING.md)

---

## Running it

Requires **Java 21**. `ffmpeg` and `ffprobe` on `PATH` for anything that touches
a video file — without them titles report "not probed" and fall back to
transcoding, which then will not work either.

```bash
# Fastest path: in-memory H2, no Docker, no Postgres
./gradlew bootRun -Ph2 --args='--spring.profiles.active=h2'

# Normal path: spring-boot-docker-compose starts Postgres from compose.yaml
./gradlew bootRun

# Tests (515, all in-process against H2)
./gradlew test

# Container — the image installs ffmpeg
docker build -t kido . && docker run -p 8080:8080 kido
```

Health check: `GET /api/health`.

### First run

1. `POST /api/auth/register` with `"role":"PARENT"`, then `POST /api/profiles`
   per family member. Keep the profile ids — most media calls are profile-scoped.
2. Point `app.media.libraries[n].path` (or `MEDIA_ROOTS`) at the drive and
   `POST /api/media/library/scan` with the admin key. Poll
   `/api/media/library/status`; the first scan runs ffprobe per file.
3. `GET /api/media/admin/matches` shows what the filename parser guessed wrong —
   worth fixing before anyone browses. `scripts/matchfix-corrections.py` batches it.

### Configuration

Everything is overridable by environment variable; see
[`application.properties`](src/main/resources/application.properties) for the
full set and the reasoning behind each default.

| Variable | Default | What it does |
| --- | --- | --- |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local Postgres | Datasource |
| `JWT_SECRET` | dev value | **Change in production.** ≥ 32 chars for HS256 |
| `ADMIN_API_KEY` | dev value | `X-Admin-Key` for owner-only endpoints |
| `ADMIN_DB_BROWSER` | `true` | Read-only `/api/admin/db` table browser; `false` removes it |
| `MEDIA_ROOTS` | empty | Directories to index; empty keeps the media module dormant |
| `FFMPEG_PATH` / `FFPROBE_PATH` | `ffmpeg` / `ffprobe` | Absolute paths if not on `PATH` |
| `MEDIA_SCAN_INTERVAL_MINUTES` | `0` | Automatic re-scan cadence; 0 disables |
| `PARTY_MAX_MEMBERS` | `4` | Seats per watch party — set by upstream bandwidth |
| `TRICKPLAY_ENABLED` | `false` | Sprite-sheet scrubbing (decodes whole files) |
| `DOWNLOADS_ENABLED` | `true` | Offline download jobs |
| `BILLING_VERIFY` | `dev` | `dev` accepts any token; `google` verifies with Play |
| `AI_SEARCH_ENABLED` | `false` | Model fallback for the search box; blank `ANTHROPIC_API_KEY` keeps it off |
| `AI_ASSISTANT_ENABLED` | `false` | Ask-a-question assistant; four read-only tools, same key |
| `FIREBASE_CREDENTIALS` | `secrets/…json` | Unset ⇒ Google sign-in off, endpoint answers 501 |

---

## Features

### Accounts, sessions and access

- Register / login with a password, or **Google sign-in** — a Firebase ID token is
  verified server-side and exchanged for this server's own JWT, so the session
  that follows is identical either way. With no Firebase credentials configured
  the endpoint answers 501 rather than failing at startup; a home media server
  should not need a Google project to boot.
- **Refresh tokens**, rotated on every use. The access token stays short-lived
  without signing devices out mid-week.
- Four kinds of caller, checked independently: anonymous, a normal account, a
  **PARENT** (owner) account, and a **guest** holding a token that reaches five
  endpoints for one film and nothing else. Owner-only endpoints need the PARENT
  role **and** the `X-Admin-Key` header — the key alone would promote any client
  it leaked into, and both failures return 403 so a response cannot confirm a
  guessed key.
- **Parent PIN** gates the parent area, stored hashed like a password.
- Single error envelope on every failure, with `410` (row exists, bytes do not —
  the drive is unplugged) and `429` (every transcode slot busy) worth handling
  separately on the client.

### Profiles

Per family member, not per account: resume points, downloads, settings,
unwatched marks and likes all hang off `X-Profile-Id`. Age mode, avatar tint,
narration and hint toggles, badges, pals and per-world progress, plus the
language and genre answers taken at first run. Progress can be pushed
incrementally or **synced in bulk** from a device that was offline — a partial
payload merges rather than overwriting, so an omitted field keeps its value.

### Media library

- Typed libraries — **Films, Anime, Ours (home video), Music, Photos** — each a
  named path, so the same disk can hold several and the client can filter by chip.
- Scans parse title, year and quality out of filenames, read sidecar metadata
  where it exists, and skip anything under a size floor so trailers and sample
  clips do not become library entries.
- **Posters and backdrops** from a per-item file or a shared per-library posters
  folder, matched by fuzzy title. Artwork below a size floor is upgraded rather
  than left alone, and a poster that has been replaced stops serving from cache.
- Optional **automatic re-scan** on a timer, and a scan-on-startup switch.
- Files a scan can no longer find are marked missing rather than deleted; an
  admin action purges them deliberately.

### Catalog and browsing

Paged, filterable browse (`category`, `unwatched`, `minHeight` for the 4K chip,
`q` across title/sort title/filename, sort by title, added, captured, year,
rating or likes), item detail, recently added, per-type library summary with
counts and bytes, genre and person lists, and a capture-date **timeline** for
home video.

### Home screen

One call returns the whole screen — continue watching, every rail, and the
library header — because the rails are ranked against each other and a phone on
house wifi pays for each extra round trip before it can draw anything.

| Rail | Ranked by |
| --- | --- |
| Popular in your library | rating `0.40` + plays `0.35` + likes `0.25`, blended |
| Top rated | rating, unrated titles excluded |
| Most watched | play count — how many times a file was opened |
| **Top viewing** | **seconds actually watched, summed across the household** |
| Most liked | like count |
| Recently added | added date |

Each signal is normalised to 0–1 before weighting, counts on a log curve against
the library's own maximum — linear scaling would let one endlessly-rewatched
favourite flatten everything else — and an unrated title is scored at the
library mean, not zero, so home footage the scraper never matched can still
surface. Every tile carries the reason it is there (`Rated 8.4`, `Played 30
times`, `Watched 3h 20m`), and empty rails are omitted rather than sent as bare
headings.

`most-watched` and `top-viewing` are deliberately adjacent: a play count only
records that a file was opened, so a title abandoned after two minutes thirty
times over leads it outright. Top viewing sums the watch increments recorded
during playback — bounded against wall-clock time when written, so a forward
seek cannot inflate them — and answers what the house actually sat through.

### Knowing what is in there

Four features over one idea: a **`CatalogQuery`** — every filter the catalog can
express, as one saveable, serialisable object.

- **Collections** are a query somebody named. Seven ship with the server as code
  (never watched, under 2 hours, 4K, hidden gems…), you can write your own, and
  the rest the library discovers in its own facets — genre, language, cast,
  decade — tallied on request so they are never stale. Pin one and it becomes a
  home rail.
- **Recommendations** are a query plus a per-profile score: `0.50` taste,
  `0.30` quality, `0.20` freshness. Taste is derived from what you finished,
  spent time on, liked, said you wanted — and **walked away from**, which is the
  only negative signal and the one that stops a recommender pushing what you
  already rejected. Every pick says why: *Because you watched Kaithi*.
- **Search by sentence** — *"Tamil films under 2 hours rated over 8"* — is a
  grammar over the library's own genres, names and languages. No model, no key,
  works offline, and it hands back what it understood so a wrong reading is
  fixed by tapping a chip rather than rephrasing at a black box.
- **Ask a question** in your own words, answered by four read-only tools over
  all of the above. *"What have we watched more than twice?"* is a tool call,
  not a new feature.

The last two can call Claude, and both are **off by default**. The search box
asks it only for sentences the grammar read nothing in; the assistant is a
separate switch. Neither can do more than the catalog already could: a model
fills in a `CatalogQuery` or calls a read-only tool, never sees the database,
and cannot name a profile — who is asking comes from the session. Every way
either can fail, from an outage to a nonsensical answer, falls back to what the
server worked out on its own.

### Playback and streaming

- **The server decides** direct play vs transcode, not the client. Post the
  device's real codec, container, height and bitrate support and use whichever
  URL comes back. Every unknown resolves toward transcoding: wasted CPU is
  recoverable, a black screen is not.
- **Direct play** serves the original bytes with byte-range support — instant
  seeking, no server CPU.
- **Transcode** produces HLS, already serving segments by the time the playlist
  is requested. Sessions are capped (each saturates several cores), idle ones
  are reaped, and the scratch directory is wiped at startup.
- `playback-decision/explain` runs the same logic with no session and no ffmpeg,
  so it still answers with the disk offline — exactly when someone is asking why
  a title will not play.
- Resume points, continue watching, subtitle track selection and per-title
  subtitle offset, and an append-only record of seconds genuinely watched
  (bounded against elapsed time, so a seek cannot inflate the totals) that backs
  the watch-hours charts and the Top viewing rail.

### Player extras

Chapter markers, **thumbnail-scrub sprite sheets** generated as an explicit
background job (generating them decodes the whole file, so it is opt-in), and a
combined player-state call so the player restores position, tracks and offset in
one request.

### Watch together

Several devices on one film, kept on the same frame over a **WebSocket** at
`/ws/party` — a pause has to land on every screen at once, which rules out
polling. Six-character join codes, a host who controls the clock, optional
approval for guests, and **guest tokens** for people with no account on the
server, rate-limited per address so codes cannot be guessed. Party size defaults
to what the upstream link actually carries (4 at ~40 Mbps), and parties die with
the process, which is honest — the sockets holding them together are gone too.

### Offline downloads and away-from-home

A copy is only re-encoded when the original will not do; a file the device can
already play is handed over untouched. Conversions use a slower preset than live
transcoding, since nothing waits on them frame by frame. Queue per profile, job
status, retention window, and a **reachability** check that answers "am I at
home?" from where the request actually arrived — Android will say it is on wifi
without knowing whose wifi — plus an estimate of what streaming from outside
would cost on a domestic upload.

### Fixing wrong metadata

A review queue of what the filename parser guessed, with edit, reclassify,
unmatch and reset per item. Batchable from `scripts/matchfix-corrections.py`.

### Likes and comments

Likes are per profile while the count is the household's, so one member's taste
never fills in another's heart, and both verbs are idempotent — a retry on a
flaky connection cannot double-count. Comments are full CRUD, per item.

### Admin panel

Health, tagged people, **live playback sessions** with the ability to stop one,
disk usage per library with reclaimable-space estimates, cache clearing, missing
item purge, artwork backfill, and demo-data seeding. Movie admin adds titles and
artwork by upload or JSON.

### Web admin dashboard

A read-only count of the whole server under `/api/admin`, for a dashboard rather
than for a phone: accounts and signups, how many signed in today and over the
last week and month, **a row per client application** (Android, iOS, web) built
from the platform each sign-in declares, and totals for everything there is to
use — movies, anime, series, video songs, home videos, music, photos, poster
templates and components, and kids-app content entries. One call returns the
whole page; each section is also fetchable on its own for a tile that refreshes
faster than the rest.

Where the admin panel above is about one household's media server, this counts
accounts across all of them. "Active" means signed in, not watched something:
playback is recorded per profile and most of what the apps do never starts a
stream.

The web console that reads it lives in [`admin-web/`](admin-web/README.md) —
React + TypeScript on Vite, `npm install && npm run dev`, proxying `/api` to
this server on port 8080.

### Database browser

Every table in the schema, read-only, a page at a time, under `/api/admin/db` —
sortable, searchable across text columns, with one row openable on its own.
Behind the same owner gate, and removable in one switch
(`ADMIN_DB_BROWSER=false`).

No endpoint takes SQL and none writes. Statements are assembled server side, all
of them `SELECT`, and a table or sort column is matched against the schema read
at startup — a name that is not a real column is refused rather than quoted into
anything. What it will not show is decided by name and type rather than a
hand-kept list, so a column added next year is covered the day it appears:
**password and token hashes are never sent**, email addresses and IPs are masked
in a listing and whole only when a single row is opened deliberately, and blobs
and JSON documents are described rather than shipped.

### Kids app side

Versioned **content manifest** so a client syncs only what changed, activity
logging with a per-profile dashboard, screen-time accounting against the daily
limit, per-profile and per-account settings, **subscription plans** with Google
Play purchase verification (or a dev verifier), **ad configuration** that returns
an empty config for paying members, and login analytics (device, IP, geo, time).

### Ceremony posters

A **template catalog** for invitation posters — marriage, birthday, baby shower
and four more ceremonies — under `/api/poster`. A template carries its whole
design as one JSON layout (background, text boxes, photo slots, sticker
placements) plus the **colour themes** that design was drawn for, and points at
shared **components**: fonts, stickers and frames the client fetches once and
caches. Coordinates are fractions of the canvas, so the same template renders on
a phone preview and a print export.

Saving a template resolves every component reference and refuses one that is
missing or of the wrong kind, and a component cannot be deleted while a template
still uses it — both of which would otherwise surface as a poster with a hole in
it. Reads need a signed-in profile; authoring needs a parent account and the
admin key.

An empty database is seeded with **1000 templates**, generated from 7 ceremonies
× 18 designs × 10 palettes rather than hand-written, so a picker is not four
cards deep on its first run. Listings are paged and `?view=summary` drops the
layouts for the grid. The endpoints and payloads are in
[`docs/poster-api.md`](docs/poster-api.md).

---

## How it is built

```
src/main/java/com/example/kido/
  auth/ security/ account/   sessions, JWT, refresh, Firebase, parent PIN
  user/ profile/ settings/   accounts, family profiles, preferences
  media/
    library/ catalog/        scanning, indexing, browsing
    home/                    rails and the popularity ranker
    stream/ playback/        the playback decision, direct play, HLS, progress
    session/                 live sessions and recorded watch time
    together/                watch parties and the WebSocket
    downloads/ trickplay/    offline copies, sprite sheets, chapters
    metadata/ matchfix/      artwork, subtitles, wrong-guess repair
    admin/ engagement/       owner tools, likes, comments
  content/ activity/ analytics/ billing/ ads/   the kids app
  poster/                    ceremony poster templates and components
  admin/                     server-wide dashboard counts, for the web console

admin-web/                   the admin console that reads them (React + Vite)
```

- **Java 21, Spring Boot 4.1**, Spring Security, Spring Data JPA.
- **PostgreSQL** in production, **H2** for tests and the `-Ph2` dev run.
- **Flyway owns the schema**; Hibernate is `ddl-auto=validate` and refuses to
  start on a mismatch. One baseline per dialect, because a `@Lob String` is
  `oid` on Postgres and `clob` on H2.
- **515 tests**, most of them full-stack integration tests over real HTTP against
  a running context — including real WebSocket connections, two accounts and
  guest tokens for watch parties.

## Known gaps

ffmpeg transcoding, download conversion and sprite generation are tested up to
the point of invoking the binary, using synthetic fixtures — a first scan of a
real library is what confirms them. Watch parties are tested end to end but not
yet across several real devices on real networks; the drift thresholds and
whether four concurrent streams fit the upstream link are the two things only
that will tell. Per-screen status against the client brief is tracked in
[`docs/api-reference.md`](docs/api-reference.md).
