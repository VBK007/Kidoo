# Tower Home Intelligence — implementation plan

Smart Collections, recommendations and natural-language search, in the order
that makes each one cheaper than the last.

The three features look like three projects. They are not. Each one is a
different front-end onto the same two questions the catalog already almost
answers:

1. **Which items?** — a filter. `CatalogService` already composes these as JPA
   `Specification`s, but they exist only as method arguments and die with the
   request.
2. **In what order?** — a score. `PopularityRanker` already does this for the
   home screen, as a pure static function tested without a database.

Make the filter a first-class object and give the scorer a per-profile input,
and all three features fall out of the same core. That is the whole plan.

```
                    CatalogQuery  ← one serialisable filter object
                          │
        ┌─────────────────┼─────────────────┐
        │                 │                 │
  Smart Collections   NL Search      Recommendations
   (saved query)   (text → query)  (query + taste score)
        │                 │                 │
        └─────────────────┼─────────────────┘
                          │
                    AI Assistant
             (tool-calls the three above)
```

The assistant is last on purpose. It is a thin layer over the other three, and
building it first would mean an LLM improvising SQL against a schema nobody had
made queryable yet.

---

## Stage 0 — what is missing before any of this works

Three gaps, found by reading the entities rather than assuming.

### 0.1 There is no language on a media item — but the data is already on disk

`"Show me Tamil movies"` is unanswerable today. `MediaItem` has genres, people,
directors, cast, studio, year, runtime, rating, certification and quality — no
language. `ProfileMediaSettings` has `preferredLanguage` (V3), so the *person*
has a language and the *film* does not.

The fix does not need a scraper or a rescan. `MediaInfo.audioTracks` already
stores `index:codec:language:title` entries straight from ffprobe, and
`embeddedSubtitles` stores `index:codec:language`. Every probed item already
carries its languages as a string nobody queries.

- Add `media_item_languages` (item id, language, `primary` flag), populated at
  probe time from the audio track list.
- Backfill from the existing `probe_audio_tracks` column — an admin action in
  the shape of the existing `backfill-artwork`, no ffprobe re-run.
- Normalise on ISO 639 codes: ffprobe reports `tam`/`ta`/`Tamil` depending on
  who muxed the file. One lookup table, applied on write.

Unprobed items have no languages; that is a known-unknown, not a bug, and it is
why `app.media.probe-on-scan` matters more after this.

### 0.2 Watched state is per profile, in another table

`PlaybackProgress.watched` is per profile. "Never watched" means *by this
profile*; "nobody in the family has watched" means *by any profile* — different
joins, and both are wanted. `CatalogService` already has an `unwatched` filter
to copy the shape from, but the household variant is new.

### 0.3 Watch history is never pruned — today

`WatchEventRepository.deleteByOccurredAtLessThan` exists and has **no caller**,
so the full history is available for a taste model. That is luck, not design. A
taste model must not silently degrade the day someone wires that sweep up, so
store the derived taste (§3) rather than recomputing from raw events forever.

---

## Stage 1 — `CatalogQuery`: one filter object

The foundation. Everything else is a consumer.

A record in `media/query/` that names every filter the catalog can express:

```
CatalogQuery(
    Set<MediaType> types,      List<String> genres,     GenreMatch genreMatch (ANY|ALL),
    List<String> people,       List<String> languages,  Range<Integer> year,
    Range<Integer> runtimeMin, Range<Double> rating,     Integer minHeight,
    WatchedBy watched (ANYONE|NOBODY|ME|NOT_ME), Boolean liked,
    String titleContains,      Sort sort,                int limit)
```

Every field optional; absent means "no opinion". Three things hang off it:

- **`CatalogQuerySpecs`** — `CatalogQuery` → `Specification<MediaItem>`, reusing
  the private specs already in `CatalogService` (`hasGenre`, `hasPerson`,
  `atLeastHeight`, `matchesTitle`) by lifting them out of it.
- **JSON round-trip** — it is stored in a collection row, emitted by the NL
  parser, and returned in API responses so a client can show *why* a rail
  contains what it contains.
- **`validate()`** — rejects impossible ranges and caps `limit`. Every producer
  goes through it, including the LLM in §5.

Then reimplement `GET /api/media/items` on top of it. Same request parameters,
same response, no client change — the point is to prove the object expresses
everything the catalog already does before anything is built on it.

**Why first:** if `CatalogQuery` cannot express a filter, no feature above it
can either. Better to find that out here than three stages later.

---

## Stage 2 — Smart Collections

A collection is a named `CatalogQuery` plus a reason to show it.

```sql
media_collections(
  id, owner_id, profile_id NULL,   -- NULL = household-wide
  name, icon, query_json,
  builtin_key NULL,                -- non-null for the ones the server ships
  pinned, sort_order, created_at)
```

**Built-in collections** are code, not rows — a `BuiltinCollections` class
holding the `CatalogQuery` for each, seeded by key so a later version can change
the definition without a migration:

| Collection | Query |
| --- | --- |
| Never watched | `watched=NOBODY` |
| Under 2 hours | `runtime ≤ 120` |
| 4K HDR | `minHeight=2160` |
| This year | `year=now` |
| Highly rated | `rating ≥ 8` |
| Hidden gems | `rating ≥ 7.5, playCount = 0` |

**Generated collections** are the interesting half, and they are a scan-time
job, not a query: walk the library, group by a facet, keep every group with ≥ N
members. That yields *"90s Tamil"*, *"Rajinikanth"*, *"Christopher Nolan"*,
*"Marvel"* without anybody naming them. Franchise detection is the one that
needs care — TMDB collection id when `tmdbId` is set, and a conservative
common-prefix rule otherwise (`Avengers: …`), because a loose rule groups
*The Godfather* with *The Godfather Part II* correctly and *Kaithi* with
*Kaithi (2019) 1080p* nonsensically.

Endpoints follow the existing shapes: `GET /api/media/collections` (list, with
counts), `GET /api/media/collections/{id}` (a page of items), and
`POST`/`PUT`/`DELETE` for the owner's own. The home screen gains a rail per
pinned collection, built by the same `addRail` path the rails already use.

**Cost:** low. It is `CatalogQuery` + a table + a grouping job.

---

## Stage 3 — "What should I watch?"

`PopularityRanker` answers *what is good in this library*. This answers *what is
good for this person tonight*, which is a different question and needs a second
scorer — not a change to the first one.

### The taste profile

Derived, stored, refreshed on a schedule and on demand:

```sql
media_profile_taste(
  profile_id, facet_kind,   -- GENRE | PERSON | LANGUAGE | DECADE | RUNTIME_BAND
  facet_value, weight, updated_at)
```

Weights come from signals the server already records, in descending order of how
much they mean:

| Signal | Source | Why it counts |
| --- | --- | --- |
| Finished it | `PlaybackProgress.watched` | The strongest statement available |
| Time watched | `WatchEvent` seconds | Already summed for the Top viewing rail |
| Liked | `MediaItemLike` | Deliberate, but rare |
| Stated preference | `ProfileMediaSettings` genres/language | The only signal a new profile has |
| Abandoned early | progress ≪ duration, never resumed | A **negative** weight — the one signal nothing uses today |

Normalise each facet against the profile's own maximum, exactly as
`PopularityRanker` normalises against the library's — the same reasoning
applies, for the same reason (one favourite otherwise flattens the rest).

### The scorer

`RecommendationScorer`, pure and static like `PopularityRanker`, so the ordering
is testable without a database:

```
score = taste_match × 0.45     -- overlap of item facets with profile weights
      + quality     × 0.25     -- the existing blended popularity score
      + freshness   × 0.15     -- unseen and recently added
      + availability× 0.15     -- direct-playable, on disk, right length for the hour
```

Two rules that matter more than the weights:

- **Explain every pick.** `"because you watched Kaithi"` is the feature. Carry
  the strongest contributing facet *and the item that established it* through
  the scorer, the way `Scored.reason` already carries the strongest signal.
- **Exclude what is already in flight.** Anything in continue-watching belongs
  on that row, not this one.

Degrade honestly: a profile with no history has no taste vector, so the rail
falls back to `PopularityRanker` filtered by the stated first-run genres — which
is precisely why those answers were persisted in V3.

`GET /api/media/recommendations?limit=20` → a rail; plus a home rail for
`Tonight's picks`.

**Cost:** medium. The scorer is a day; the taste derivation and its refresh job
are the work, and the honest evaluation of whether the picks are any good is the
part that takes longest.

---

## Stage 4 — Natural-language search, deterministic first

`"Tamil movies under 2 hours with rating > 8"` → `CatalogQuery`. No LLM.

A grammar over a **closed vocabulary the server already owns**: genres and
people come from `findDistinctGenres()` / `findDistinctPeople()`, languages from
§0.1, type labels from `MediaType`. That vocabulary is what makes a rule-based
parser viable here — the hard part of NL search is usually resolving entities,
and this library's entities are a list the server can print.

Patterns worth handling, in rough order of how often they appear:

```
<language> · <genre> · <person>              → equality filters
under/over N hours|minutes                   → runtime range
rating above/over N  ·  N+ rating            → rating range
from the 90s · 2015 · before 2000            → year range
4K · HD                                      → minHeight
unwatched · never watched · nobody has seen  → WatchedBy
I liked · we liked                           → liked
```

Returns the parsed `CatalogQuery` **alongside** the results, so the client can
render it as removable chips — the user sees what the server understood, and
fixes it by tapping, not by rephrasing. That display is what makes the parser's
inevitable misses survivable.

`GET /api/media/search?q=<text>` → `{ query, interpretation, items }`.

**Cost:** medium. Works offline, costs nothing per call, and handles the
phrasings people actually type — which is most of them.

---

## Stage 5 — The LLM translator, behind a flag

For everything the grammar misses. The design rule is one sentence:

> **The model emits a `CatalogQuery`. It never sees the database and never
> writes SQL.**

The same validated object the deterministic parser produces, so everything
downstream — specs, paging, security, tests — is unchanged and already proven.
The model's blast radius is a filter it is allowed to get wrong.

**Flow:** deterministic parser first; if it extracts nothing, or the client asks
for it explicitly, fall through to the model. Never call it for a query the
grammar already understood — that is latency and money for a worse answer.

**Concretely:**

- Dependency `com.anthropic:anthropic-java:2.34.0`; client from
  `AnthropicOkHttpClient.fromEnv()`.
- Model `claude-opus-5`, adaptive thinking (`ThinkingConfigAdaptive`). This is a
  short translation task, so `OutputConfig.effort(LOW)` is the knob to try first
  if latency or cost matters — measure before assuming.
- **Structured outputs** (`output_config.format`) against the `CatalogQuery`
  schema, so the response is a valid object rather than JSON to be salvaged out
  of prose.
- **Prompt caching** on the system prompt. It carries the schema plus the
  library's genre/person/language vocabulary — large, and identical between
  requests, which is exactly the shape caching pays for. Rebuild it only when a
  scan changes the vocabulary; check `cacheReadInputTokens` to confirm it is
  actually hitting.
- `app.media.ai.enabled=false` by default, `ANTHROPIC_API_KEY` from the
  environment. **Unset ⇒ the endpoint answers exactly as it does today, from the
  grammar alone.** Same discipline as `app.firebase.credentials`: a home media
  server must not need an account somewhere to start, and must not stop working
  when the internet does.
- Cache translations by normalised query text. Households ask the same question
  repeatedly.

**Cost:** low, given §1 and §4 exist. It is one class, one schema and a flag.
This is the whole reason for building the deterministic layer first.

---

## Stage 6 — The assistant

Only now, and only as tool-calling over what already exists: `CatalogQuery`
search, the taste profile, watch history aggregates, collections. Questions like
*"what have we watched more than twice"* and *"what has nobody seen"* are then
tool calls, not new features — the SDK's tool runner drives the loop.

Anything it cannot answer with those tools is a missing tool, which is a
concrete, small piece of work rather than a prompt to be tuned.

---

## Order, and why

| Stage | Ships | Depends on | Size | Status |
| --- | --- | --- | --- | --- |
| 0.1 Languages | A facet three features need | — | S | **done** |
| 1 `CatalogQuery` | Nothing visible | 0.1 | M | **done** |
| 2 Smart Collections | Real, visible feature | 1 | M | **done** |
| 3 Recommendations | The headline feature | 1, taste model | L | next |
| 4 NL search (rules) | Works offline, free | 1 | M | |
| 5 NL search (LLM) | Covers the rest | 4 | S | |
| 6 Assistant | The distinctive one | 1–5 | M | |

Stage 1 is the only stage that ships nothing a user can see, and it is the one
that makes stages 2, 4 and 5 small. Doing it out of order means writing the
filter logic three times.

## Rules this plan holds itself to

Taken from what the codebase already does, not invented here:

- **Scorers are pure and static**, like `PopularityRanker` — the ordering is the
  judgement, and judgement should be testable without a database.
- **Every migration is written for both dialects**, `postgresql/` and `h2/`,
  because Hibernate validates against a live schema and the tests run on H2.
- **Every rail and every pick carries its reason.** The existing home screen
  already refuses to show a number without saying what it means; recommendations
  are worthless without it.
- **Degrade, don't fail.** No API key, no history, no probe data — each of those
  is a narrower answer, never an error.
- **The server composes; the client renders.** One call per screen, as
  `HomeService` already does.

## Deferred, with a note on where they land

From the wider feature list, these fit the same core and can slot in later:
**skip intro/credits** (a background ffmpeg job beside trickplay, four columns on
`media_item`), **storage intelligence** (`reclaimableStats` already does the hard
query; it needs duplicate detection and a review screen), **household continue
watching** (a second query beside `playback.continueWatching`, plus a
"Continue together" button onto the existing party API), **achievements** and
**device management** (both mostly reads over data already recorded), and
**notifications** (needs a push transport decision first — that one is not a
backend-only feature).
