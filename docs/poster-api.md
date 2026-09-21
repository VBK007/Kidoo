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

---

## Endpoints

| Method | Path | Who | What |
| --- | --- | --- | --- |
| `GET` | `/api/poster/templates` | any profile | Every published template, ordered by category, then `sortOrder`, then name. `?includeDrafts=true` (owner only) also returns unpublished rows. |
| `GET` | `/api/poster/templates/{category}` | any profile | One ceremony's templates. |
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

`GET /api/poster/templates/marriage`, abridged — one seeded template:

```json
[
  {
    "id": "8c887524-9397-46a8-868b-0b001cf98c6b",
    "category": "MARRIAGE",
    "categorySlug": "marriage",
    "name": "Maroon & Gold Mandala",
    "thumbnail": "https://assets.kiduu.app/poster/thumbnails/marriage-maroon-gold.jpg",
    "layout": {
      "backgroundColor": "#FDF6EC",
      "backgroundImageUrl": null,
      "canvasWidth": 1080,
      "canvasHeight": 1350,
      "fontComponentId": "fc06a033-9856-40ef-b071-9984f90c63a3",
      "textBoxes": [
        {
          "key": "couple",
          "label": "Couple's names",
          "text": "Aarav  &  Diya",
          "x": 0.08, "y": 0.17, "width": 0.84, "height": 0.12,
          "fontSize": 0.075,
          "fontComponentId": null,
          "color": "#7B1E3A",
          "align": "center"
        }
      ],
      "imageSlots": [
        {
          "key": "couple-photo",
          "label": "Couple's photograph",
          "x": 0.28, "y": 0.31, "width": 0.44, "height": 0.27,
          "shape": "circle",
          "frameComponentId": "36bf64c1-9c7d-40f7-92d5-20b4ef5111d1"
        }
      ],
      "stickers": [
        {
          "key": "corner-top-left",
          "componentId": "7092448b-f718-4d54-9ddd-478ea041e8b7",
          "x": 0.02, "y": 0.02, "width": 0.22, "height": 0.18,
          "rotation": 0
        }
      ]
    },
    "colorThemes": [
      { "name": "Maroon & Gold", "primary": "#7B1E3A", "secondary": "#D4AF37" },
      { "name": "Ivory & Rose",  "primary": "#B5838D", "secondary": "#F4E3C1" },
      { "name": "Emerald & Gold", "primary": "#1E5945", "secondary": "#D4AF37" }
    ],
    "published": true,
    "sortOrder": 0,
    "updatedAt": "2026-09-21T08:25:26.165394Z"
  }
]
```

The layout carries component **ids**, not the components themselves: the same
font and the same mandala appear across most of a category, and inlining them
would send the same bytes a dozen times in one response. Fetch
`GET /api/poster/components` once, cache it, and resolve against it:

```json
[
  {
    "id": "36bf64c1-9c7d-40f7-92d5-20b4ef5111d1",
    "type": "FRAME",
    "name": "Gold Beaded Ring",
    "url": null,
    "properties": { "borderColor": "#D4AF37", "borderWidth": 6, "style": "beaded" },
    "updatedAt": "2026-09-21T08:25:26.156395Z"
  }
]
```

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

One sample template per ceremony (Marriage, Birthday, Baby Shower) plus the
components they use are written on the first start of an **empty** catalog, so
the module answers with something before anyone has authored anything. It never
re-seeds: delete a sample and it stays deleted.

```properties
app.poster.seed-samples=true
# This server does not host the artwork. Point this at your own bucket.
app.poster.asset-base-url=https://assets.kiduu.app/poster
```
