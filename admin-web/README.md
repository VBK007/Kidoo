# Kido admin console

The web view of `/api/admin`: how many people use the server, from which apps,
and how much there is for them to use. React + TypeScript on Vite.

Read-only. Nothing in here can change anything on the server — there is no
endpoint behind it that does.

## Running it

```bash
npm install
npm run dev            # http://localhost:5173, proxying /api to localhost:8080
```

The backend has to be up. From the repository root:

```bash
./gradlew bootRun -Ph2 --args='--spring.profiles.active=h2'
```

Point the proxy somewhere else with `KIDO_API`:

```bash
KIDO_API=http://192.168.1.10:8080 npm run dev
```

`npm run build` emits a static `dist/`. Serve it behind the same host that
serves the API and it works unchanged; if it cannot be the same origin, build
with `VITE_API_BASE=https://api.example.com` and the calls go there instead.
The API already allows any origin, so that works — same-origin is simply one
fewer thing between the console and an answer.

## Signing in

Both halves of the server's owner gate, on one form:

- a **PARENT** account's username/email and password, and
- the **admin key** — `ADMIN_API_KEY` on the server, sent as `X-Admin-Key`.

Either alone is refused with a 403 that does not say which half failed, so a
response can never confirm a guessed key. The console checks the role itself
after signing in, purely so it can give the reason the server deliberately
will not.

Both are kept in `sessionStorage` and nowhere else: closing the tab ends the
session, and the admin key never enters the bundle. A 401 or 403 mid-session
signs out rather than leaving a page of stale numbers on screen.

## What is on it

| Section | Reads |
| --- | --- |
| Hero + People | `GET /api/admin/dashboard` → `users`, `engagement` |
| Applications | `applications` — one row per client app |
| Catalog | `catalog.library`, `catalog.posters`, `catalog.content` |
| Usage | watch hours, content, the count timestamp |

The **Live** toggle re-polls `GET /api/admin/engagement` every 15 seconds —
only that one, because the full dashboard re-counts a four-figure poster
catalog and no live tile is worth making the whole page pay for. **Refresh**
re-reads everything.

Two things the page says out loud, because both are easy to misread:

- **Active means signed in**, not watched something. Playback is recorded per
  profile and carries no account, and most of what the apps do never starts a
  stream.
- **The application rows do not sum to the account total.** Someone who uses
  the phone and the browser is counted in both.

## Design

Charts follow the house data-viz rules. Bars are a single sequential blue —
every list here compares magnitude, never identity, so categorical colour
would spend eight hues saying what position on a shared baseline already says.
Numbers are direct-labelled rather than read off an axis, empty categories keep
their row at zero, and the applications panel is a table because six columns
that all carry meaning are a table, not a chart. Dark mode is a selected set of
steps for the dark surface, following the OS until the header toggle overrides
it.

## Layout

```
src/
  api.ts              the wire contract, typed against DashboardDtos.java
  format.ts           numbers, bytes, relative time — shared so tiles agree
  App.tsx             signed in or not
  components/
    SignIn.tsx        account + admin key
    Dashboard.tsx     the page, the live poll, the theme toggle
    Applications.tsx  the per-app table
    BarList.tsx       magnitude bars
    Tiles.tsx         hero, stat tile, card
```

`npm run lint` (oxlint) and `npm run build` (which type-checks first) both need
to be clean.
