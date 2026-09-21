# Ceremony poster API

The contract for the poster creator: a catalog of **templates** (whole designs)
built out of shared **components** (fonts, stickers, frames). Everything lives
under `/api/poster`.

Field names are taken from the Java DTOs in `com.example.kido.poster`, and the
example response below was captured from a running server, so they match the
wire exactly.

---

## Headers

| Header | When | Notes |
| --- | --- | --- |
| `Authorization: Bearer <jwt>` | Every call | Reading needs any signed-in profile. No token is **403**, as everywhere else in this app. |
| `X-Admin-Key: <key>` | `POST`, `PUT`, `DELETE`, and `?includeDrafts=true` | Required **in addition to** a PARENT account, matching `/api/media/admin/**`. Failure is **403** either way, so a response cannot confirm a guessed key. |

Errors use the app-wide envelope: `{timestamp, status, error, message}`.

## Page envelope

The two listing endpoints are paged, in the same envelope the media catalog uses.
`size` defaults to **40** and is **capped at 100** — a seeded catalog is a
thousand templates, and a thousand full layouts is tens of megabytes:

```json
{ "items": [], "page": 0, "size": 40, "totalItems": 1000, "totalPages": 25 }
```

`?view=summary` drops `layout` from each item, which is the bulk of it. Measured
on the seeded catalog, a page of 40 is **73 KB** full and **21 KB** as
summaries. Use summaries for the picker grid and fetch the whole template by id
when somebody opens one.

---

## Endpoints

| Method | Path | Who | What |
| --- | --- | --- | --- |
| `GET` | `/api/poster/templates` | any profile | A page of published templates, ordered by category, then `sortOrder`, then name. `?page=`, `?size=`, `?view=summary`. `?includeDrafts=true` (owner only) also returns unpublished rows. |
| `GET` | `/api/poster/templates/{category}` | any profile | One ceremony's templates, same parameters. |
| `GET` | `/api/poster/templates/by-id/{id}` | any profile | A single template. |
| `POST` | `/api/poster/templates` | owner | Creates one. **201**. |
| `PUT` | `/api/poster/templates/{id}` | owner | **Replaces** it whole — see below. |
| `DELETE` | `/api/poster/templates/{id}` | owner | **204**. |
| `GET` | `/api/poster/components` | any profile | The whole catalog; `?type=font\|sticker\|frame` filters it. |
| `POST` | `/api/poster/components` | owner | Creates one. **201**. |
| `PUT` | `/api/poster/components/{id}` | owner | Replaces it whole; the id survives, so references still resolve. |
| `DELETE` | `/api/poster/components/{id}` | owner | **204**, or **409** while a template still uses it. |

`GET /{category}` takes a category and not an id. That is unambiguous because
the categories are a closed set, and it is why fetching one template by id has
its own `/by-id/` path.

### Categories

`MARRIAGE`, `ENGAGEMENT`, `BIRTHDAY`, `BABY_SHOWER`, `NAMING_CEREMONY`,
`HOUSE_WARMING`, `ANNIVERSARY`.

Spelling is forgiving on the way in: `marriage`, `baby-shower`, `"baby shower"`
and `BABY_SHOWER` all work, in the path and in a request body. Anything else is
a **400** that names every valid value. Responses always carry both forms —
`category` is the enum name, `categorySlug` is what goes back in a URL.

### Component types

| Type | Carries |
| --- | --- |
| `FONT` | `url` (required) — the woff2/ttf the editor loads. |
| `STICKER` | `url` (required) — the artwork. |
| `FRAME` | `properties` — `borderColor`, `borderWidth`, `cornerRadius`, `style`, whatever the renderer reads. `url` optional. |

`properties` is an open JSON object on purpose: it is the extension point, and
nothing on the server reads it.

---

## The layout

A template's whole design is one JSON document, because it is only ever read and
written whole.

**Coordinates and `fontSize` are fractions of the canvas**, 0.0–1.0, not pixels:
a phone preview, a tablet editor and a print export are the same design at three
sizes. `canvasWidth` and `canvasHeight` give the aspect ratio those fractions
are read against (defaults 1080×1350).

```
layout
  backgroundColor      hex, required
  backgroundImageUrl   optional, drawn over the colour
  canvasWidth/Height   1–10000
  fontComponentId      the template's default FONT
  textBoxes[]          key, label, text, x, y, width, height, fontSize,
                       fontComponentId (overrides the default), color, align
  imageSlots[]         key, label, x, y, width, height,
                       shape (rect|circle|arch), frameComponentId
  stickers[]           key, componentId, x, y, width, height, rotation
colorThemes[]          name, primary, secondary — one tap recolours the poster
```

`colorThemes` is never empty in a response: a template saved without any is
given its own colours back as a single `Default` theme, so the editor always has
a swatch row to draw.

### What a save refuses

- A category outside the set, a colour that is not hex, a coordinate outside
  0–1, a missing `key` — **400** from bean validation.
- A `componentId`, `fontComponentId` or `frameComponentId` nothing resolves to —
  **400**, `Unknown STICKER component '…'`.
- A component used as the wrong kind, e.g. a font where a sticker belongs —
  **400**, `Component '…' is a FONT but the layout uses it as a STICKER`.

The mirror of that last rule is on the component side: deleting a component, or
changing its type, is **409** while a template still points at it. Both
directions exist because a dangling reference does not fail anywhere — it just
renders a poster with a hole in it.

`PUT` replaces a template rather than patching it. A layout only means anything
entire; a request that changed the text boxes and left last week's sticker
placements behind would save a design nobody drew.

---

## Example

`GET /api/poster/templates/marriage?page=6&size=1`, abridged — one template out
of the 143 the seeded catalog holds for this ceremony:

```json
{
  "items": [
    {
      "id": "d51ad995-e149-4e36-8ee9-2064f664110e",
      "category": "MARRIAGE",
      "categorySlug": "marriage",
      "name": "Pastel Pink Classic Scroll Corners",
      "thumbnail": "https://assets.kiduu.app/poster/thumbnails/marriage-classic-scroll-corners-pastel-pink.jpg",
      "layout": {
        "backgroundColor": "#FCE4EC",
        "backgroundImageUrl": null,
        "canvasWidth": 1080,
        "canvasHeight": 1350,
        "fontComponentId": "15986159-d951-4a8d-ae8c-8abf3679c928",
        "textBoxes": [
          {
            "key": "heading",
            "label": "Heading",
            "text": "Wedding Invitation",
            "x": 0.1, "y": 0.07, "width": 0.8, "height": 0.07,
            "fontSize": 0.035,
            "fontComponentId": null,
            "color": "#AD1457",
            "align": "center"
          },
          {
            "key": "names",
            "label": "Names",
            "text": "Aarav  &  Diya",
            "x": 0.08, "y": 0.16, "width": 0.84, "height": 0.12,
            "fontSize": 0.072,
            "fontComponentId": null,
            "color": "#AD1457",
            "align": "center"
          }
        ],
        "imageSlots": [
          {
            "key": "photo",
            "label": "Photograph",
            "x": 0.28, "y": 0.31, "width": 0.44, "height": 0.27,
            "shape": "circle",
            "frameComponentId": "2d5e1ae3-6616-4583-902b-233a4dd9ce53"
          }
        ],
        "stickers": [
          {
            "key": "corner-top-left",
            "componentId": "35732130-0e94-4b4a-9b75-770bce09e2b8",
            "x": 0.02, "y": 0.02, "width": 0.22, "height": 0.18,
            "rotation": 0
          },
          {
            "key": "corner-bottom-right",
            "componentId": "35732130-0e94-4b4a-9b75-770bce09e2b8",
            "x": 0.76, "y": 0.8, "width": 0.22, "height": 0.18,
            "rotation": 180
          }
        ]
      },
      "colorThemes": [
        { "name": "Pastel Pink",   "primary": "#AD1457", "secondary": "#F8BBD0" },
        { "name": "Royal Gold",    "primary": "#8D6E00", "secondary": "#FFECB3" },
        { "name": "Maroon & Gold", "primary": "#7B1E3A", "secondary": "#D4AF37" }
      ],
      "published": true,
      "sortOrder": 7,
      "updatedAt": "2026-09-21T14:22:24.735742Z"
    }
  ],
  "page": 6,
  "size": 1,
  "totalItems": 143,
  "totalPages": 143
}
```

The layout carries component **ids**, not the components themselves: the same
font and the same mandala appear across most of a category, and inlining them
would send the same bytes a dozen times in one response. Fetch
`GET /api/poster/components` once, cache it, and resolve against it:

```json
[
  {
    "id": "61d26a88-129f-4bb9-9a47-0e3518199ec6",
    "type": "FRAME",
    "name": "Circle Maroon",
    "url": null,
    "properties": { "shape": "circle", "borderColor": "#880E4F", "borderWidth": 4 },
    "updatedAt": "2026-09-21T14:22:24.716118Z"
  }
]
```

There are 20 of them behind a thousand templates — five fonts, ten stickers and
five frames — which is the whole point of them being rows.

### Creating one

```http
POST /api/poster/templates
Authorization: Bearer <parent jwt>
X-Admin-Key: <key>

{
  "category": "baby-shower",
  "name": "Pastel Clouds II",
  "thumbnail": "https://assets.example/thumbnails/pastel-clouds-ii.jpg",
  "layout": {
    "backgroundColor": "#EAF4FB",
    "canvasWidth": 1080,
    "canvasHeight": 1350,
    "fontComponentId": "<a FONT component id>",
    "textBoxes": [
      { "key": "heading", "label": "Heading", "text": "Baby Shower",
        "x": 0.08, "y": 0.09, "width": 0.84, "height": 0.08,
        "fontSize": 0.05, "color": "#5B7DB1", "align": "center" }
    ],
    "imageSlots": [],
    "stickers": []
  },
  "colorThemes": [
    { "name": "Sky", "primary": "#5B7DB1", "secondary": "#C7E0F4" }
  ],
  "published": true,
  "sortOrder": 0
}
```

---

## Storage and seeding

`layout`, `colorThemes` and a component's `properties` are `jsonb` on PostgreSQL
(`json` on H2) — see `db/migration/postgresql/V12__poster_templates.sql` and its
H2 twin. Rows per text box would buy joins and nothing else, and every new kind
of element a designer wanted would be a migration.

A catalog of **1000 templates** plus the 20 components they use is written on
the first start of an **empty** database, so the module answers with something
before anyone has authored anything. It never re-seeds: delete a template and it
stays deleted. Seeding 1000 rows takes about 1.5 seconds and runs after the
server is already accepting requests.

They are generated rather than hand-written — **7 ceremonies × 18 designs × 10
palettes**, 1260 distinct combinations, taken in a fixed order:

| Axis | Values |
| --- | --- |
| Ceremony | the seven categories; each brings its own wording, typeface, motif and frame |
| Skeleton | Classic Scroll, Photo First, Centre Medallion, Side by Side, Minimal Card, Festive Border |
| Arrangement | Clean (no stickers), Corners (a motif in two corners), Band (a garland along the foot) |
| Palette | Pastel Pink, Royal Gold, Maroon & Gold, Bright Yellow, Party Blue, Fresh Mint, Lavender, Terracotta, Emerald, Midnight |

A name is the palette and the design — `Pastel Pink Classic Scroll Corners` —
and `sortOrder` is the template's position within its ceremony. Nothing is
random: the same `seed-count` always produces the same catalog in the same
order, and raising it on an existing install would add designs rather than
rename the ones already there. The ceremony cycles fastest, so the first seven
templates are one per ceremony and any prefix of the catalog is evenly spread.

Past 1260 the combinations wrap and designs repeat under new names; the seeder
logs a warning when `seed-count` is set that high.

```properties
app.poster.seed-samples=true
app.poster.seed-count=1000
# This server does not host the artwork. Point this at your own bucket.
app.poster.asset-base-url=https://assets.kiduu.app/poster
```
