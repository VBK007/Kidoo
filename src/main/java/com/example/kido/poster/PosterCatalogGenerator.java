package com.example.kido.poster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.kido.poster.PosterLayout.ImageSlot;
import com.example.kido.poster.PosterLayout.StickerPlacement;
import com.example.kido.poster.PosterLayout.TextBox;

/**
 * Builds a catalog of templates out of a few dozen decisions instead of a few thousand.
 *
 * <p>A poster catalog wants volume — a picker with four marriage cards in it looks
 * broken — but a thousand hand-written rows is a thousand chances to typo a hex colour
 * and no way to fix a bad margin everywhere at once. So the catalog is a product:
 * <b>7 ceremonies × 18 designs × 10 palettes</b>, 1260 combinations, generated in a
 * fixed order. Fix a skeleton here and every template built on it is fixed.
 *
 * <p>Deterministic on purpose — no randomness anywhere. The same {@code count} always
 * produces the same catalog in the same order, so a template someone reports a problem
 * with can be reproduced, and re-seeding a wiped database gives back the same designs
 * rather than a new shuffle.
 *
 * <p>Past 1260 the combinations wrap and designs repeat. Names stay unique because each
 * carries its ordinal within its ceremony.
 */
final class PosterCatalogGenerator {
    private PosterCatalogGenerator() {}

    /** How many distinct designs exist before the combinations start repeating. */
    static final int DISTINCT_COMBINATIONS =
            PosterCategory.values().length * 6 * Arrangement.values().length * 10;

    // --- the three axes ----------------------------------------------------------

    /**
     * A palette, and the shade of body text that stays readable on it.
     *
     * <p>{@code background} is the page behind everything; {@code primary} carries the
     * headline; {@code ink} is for the date and the venue, which have to be read rather
     * than admired.
     */
    private record Palette(String name, String primary, String secondary,
                           String background, String ink) {}

    private static final List<Palette> PALETTES = List.of(
            new Palette("Pastel Pink", "#AD1457", "#F8BBD0", "#FCE4EC", "#5D2B3C"),
            new Palette("Royal Gold", "#8D6E00", "#FFECB3", "#FFFDE7", "#4A3B00"),
            new Palette("Maroon & Gold", "#7B1E3A", "#D4AF37", "#FDF6EC", "#3B2314"),
            new Palette("Bright Yellow", "#E65100", "#FFF176", "#FFF9C4", "#5D3A00"),
            new Palette("Party Blue", "#1565C0", "#64B5F6", "#E3F2FD", "#0D3C69"),
            new Palette("Fresh Mint", "#2E7D6F", "#A5D6C7", "#E6F4F1", "#1B4740"),
            new Palette("Lavender", "#6A3FB5", "#D1C4E9", "#F3E9FB", "#3A2465"),
            new Palette("Terracotta", "#B5442A", "#F0B49E", "#FBEDE7", "#5E2415"),
            new Palette("Emerald", "#1E5945", "#9CCFB8", "#EAF5EF", "#123328"),
            new Palette("Midnight", "#2A3A64", "#8FA2D6", "#EDF1FA", "#1A2440"));

    /** Where the text and the photograph sit. Six of these, each recoloured ten ways. */
    private record Skeleton(String name, List<BoxSpec> boxes, ImageSlot slot) {}

    /**
     * One text box's geometry and which colour it takes.
     *
     * @param role  becomes the box's {@code key}; the wording comes from the ceremony
     * @param shade {@code primary}, {@code secondary} or {@code ink}
     */
    private record BoxSpec(String role, double x, double y, double width, double height,
                           double fontSize, String shade, String align) {}

    private static final List<Skeleton> SKELETONS = List.of(
            new Skeleton("Classic Scroll", List.of(
                    new BoxSpec("heading", 0.10, 0.07, 0.80, 0.07, 0.035, "primary", "center"),
                    new BoxSpec("names", 0.08, 0.16, 0.84, 0.12, 0.072, "primary", "center"),
                    new BoxSpec("date", 0.10, 0.63, 0.80, 0.05, 0.028, "ink", "center"),
                    new BoxSpec("venue", 0.10, 0.70, 0.80, 0.06, 0.026, "ink", "center"),
                    new BoxSpec("note", 0.12, 0.79, 0.76, 0.05, 0.021, "secondary", "center")),
                    new ImageSlot("photo", "Photograph", 0.28, 0.31, 0.44, 0.27, "circle", null)),

            new Skeleton("Photo First", List.of(
                    new BoxSpec("heading", 0.08, 0.45, 0.84, 0.07, 0.040, "primary", "center"),
                    new BoxSpec("names", 0.08, 0.54, 0.84, 0.10, 0.060, "primary", "center"),
                    new BoxSpec("date", 0.10, 0.68, 0.80, 0.05, 0.028, "ink", "center"),
                    new BoxSpec("venue", 0.10, 0.75, 0.80, 0.06, 0.026, "ink", "center")),
                    new ImageSlot("photo", "Photograph", 0.12, 0.05, 0.76, 0.34, "rect", null)),

            new Skeleton("Centre Medallion", List.of(
                    new BoxSpec("heading", 0.10, 0.09, 0.80, 0.06, 0.034, "primary", "center"),
                    new BoxSpec("names", 0.08, 0.60, 0.84, 0.10, 0.062, "primary", "center"),
                    new BoxSpec("date", 0.10, 0.72, 0.80, 0.05, 0.027, "ink", "center"),
                    new BoxSpec("venue", 0.10, 0.79, 0.80, 0.06, 0.025, "ink", "center")),
                    new ImageSlot("photo", "Photograph", 0.26, 0.19, 0.48, 0.36, "circle", null)),

            new Skeleton("Side by Side", List.of(
                    new BoxSpec("heading", 0.50, 0.30, 0.42, 0.07, 0.032, "primary", "left"),
                    new BoxSpec("names", 0.50, 0.39, 0.42, 0.11, 0.050, "primary", "left"),
                    new BoxSpec("date", 0.50, 0.52, 0.42, 0.05, 0.025, "ink", "left"),
                    new BoxSpec("venue", 0.50, 0.58, 0.42, 0.06, 0.023, "ink", "left"),
                    new BoxSpec("note", 0.10, 0.82, 0.80, 0.05, 0.021, "secondary", "center")),
                    new ImageSlot("photo", "Photograph", 0.08, 0.30, 0.36, 0.34, "arch", null)),

            new Skeleton("Minimal Card", List.of(
                    new BoxSpec("heading", 0.10, 0.22, 0.80, 0.09, 0.046, "primary", "center"),
                    new BoxSpec("names", 0.08, 0.35, 0.84, 0.14, 0.086, "primary", "center"),
                    new BoxSpec("date", 0.10, 0.56, 0.80, 0.06, 0.030, "ink", "center"),
                    new BoxSpec("venue", 0.10, 0.64, 0.80, 0.06, 0.027, "ink", "center"),
                    new BoxSpec("note", 0.12, 0.74, 0.76, 0.05, 0.022, "secondary", "center")),
                    null),

            new Skeleton("Festive Border", List.of(
                    new BoxSpec("heading", 0.14, 0.14, 0.72, 0.07, 0.036, "primary", "center"),
                    new BoxSpec("names", 0.12, 0.23, 0.76, 0.11, 0.064, "primary", "center"),
                    new BoxSpec("date", 0.14, 0.66, 0.72, 0.05, 0.027, "ink", "center"),
                    new BoxSpec("venue", 0.14, 0.73, 0.72, 0.06, 0.025, "ink", "center")),
                    new ImageSlot("photo", "Photograph", 0.30, 0.37, 0.40, 0.25, "rect", null)));

    /**
     * What the ceremony's own artwork does on top of a skeleton.
     *
     * <p>The third axis, and the cheapest one: the same arrangement of the same two
     * stickers reads completely differently under a marriage's mandala and a birthday's
     * balloons, because the stickers come from the ceremony rather than from here.
     */
    private enum Arrangement {
        CLEAN("Clean"),
        CORNERS("Corners"),
        BAND("Band");

        private final String suffix;

        Arrangement(String suffix) {
            this.suffix = suffix;
        }

        List<StickerPlacement> placements(String motifId, String bandId) {
            return switch (this) {
                case CLEAN -> List.of();
                case CORNERS -> List.of(
                        new StickerPlacement("corner-top-left", motifId, 0.02, 0.02, 0.22, 0.18, 0),
                        new StickerPlacement("corner-bottom-right", motifId, 0.76, 0.80, 0.22, 0.18, 180));
                case BAND -> List.of(
                        new StickerPlacement("band", bandId, 0.10, 0.88, 0.80, 0.10, 0),
                        new StickerPlacement("corner-top-right", motifId, 0.74, 0.03, 0.22, 0.16, 12));
            };
        }
    }

    // --- what each ceremony brings -----------------------------------------------

    /**
     * The half of a template that is about the occasion rather than the design: its
     * wording, its typeface and its artwork.
     *
     * @param motif a sticker small enough to sit in a corner
     * @param band  a sticker wide enough to run along the foot of the poster
     */
    private record Ceremony(String heading, String names, String date, String venue, String note,
                            String font, String motif, String band, String frame) {}

    private static final Map<PosterCategory, Ceremony> CEREMONIES = Map.of(
            PosterCategory.MARRIAGE, new Ceremony(
                    "Wedding Invitation", "Aarav  &  Diya",
                    "Sunday, 14 February 2027  ·  7:30 pm", "Kalyana Mandapam, Coimbatore",
                    "Together with their families",
                    "Great Vibes", "Gold Mandala Corner", "Marigold Garland", "Gold Beaded Ring"),

            PosterCategory.ENGAGEMENT, new Ceremony(
                    "Engagement Ceremony", "Rohan  &  Priya",
                    "Saturday, 6 December  ·  6 pm", "The Orchid Hall, Chennai",
                    "Do join us for the evening",
                    "Great Vibes", "Wedding Ring", "Marigold Garland", "Circle Maroon"),

            PosterCategory.BIRTHDAY, new Ceremony(
                    "Happy Birthday!", "Meera turns 5",
                    "Saturday, 9 May  ·  4 pm", "Flat 402, Lake View Apartments",
                    "Cake, games and a lot of noise",
                    "Comic Sans MS", "Balloon", "Confetti Burst", "Rectangle Amber"),

            PosterCategory.BABY_SHOWER, new Ceremony(
                    "Baby Shower", "for Nithya  &  Karthik",
                    "Sunday, 2 August  ·  11 am", "Rose Hall, Race Course Road",
                    "Your blessings are the only gift we need",
                    "Quicksand", "Pastel Cloud", "Baby Pram", "Rounded Blue"),

            PosterCategory.NAMING_CEREMONY, new Ceremony(
                    "Naming Ceremony", "Our little one",
                    "Friday, 21 March  ·  10 am", "Sree Temple, Alwarpet",
                    "Come bless the baby",
                    "Quicksand", "Baby Icon", "Pastel Cloud", "Rounded Blue"),

            PosterCategory.HOUSE_WARMING, new Ceremony(
                    "Gruhapravesam", "The Sharma family",
                    "Thursday, 18 July  ·  7 am", "No. 12, Jasmine Street, Adyar",
                    "Breakfast will be served",
                    "Pacifico", "Diya Lamp", "Marigold Garland", "Rectangle Amber"),

            PosterCategory.ANNIVERSARY, new Ceremony(
                    "Happy Anniversary", "25 years together",
                    "Monday, 3 November  ·  7 pm", "Terrace Garden, Bengaluru",
                    "Dinner and old photographs",
                    "Pacifico", "Champagne Toast", "Confetti Burst", "Circle Maroon"));

    /** Every component name the generated catalog refers to. */
    static List<String> requiredComponentNames() {
        List<String> names = new ArrayList<>();
        for (Ceremony ceremony : CEREMONIES.values()) {
            names.add(ceremony.font());
            names.add(ceremony.motif());
            names.add(ceremony.band());
            names.add(ceremony.frame());
        }
        return names.stream().distinct().sorted().toList();
    }

    // --- generation ---------------------------------------------------------------

    /**
     * @param count       how many templates to build
     * @param componentId resolves a component's name to its id — the generated layouts
     *                    reference ids, so this is what ties them to the catalog
     * @param assetBase   where the thumbnails live
     */
    static List<PosterTemplate> generate(int count, Map<String, String> componentId, String assetBase) {
        List<PosterCategory> categories = List.of(PosterCategory.values());
        List<Arrangement> arrangements = List.of(Arrangement.values());
        Map<PosterCategory, Integer> ordinals = new LinkedHashMap<>();

        List<PosterTemplate> built = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            // Category cycles fastest so that any prefix of the catalog — the first
            // ten, the first hundred — is spread evenly over the ceremonies rather
            // than being all marriage cards.
            int rest = i / categories.size();
            PosterCategory category = categories.get(i % categories.size());
            Skeleton skeleton = SKELETONS.get(rest % SKELETONS.size());
            rest /= SKELETONS.size();
            Arrangement arrangement = arrangements.get(rest % arrangements.size());
            rest /= arrangements.size();
            Palette palette = PALETTES.get(rest % PALETTES.size());

            int ordinal = ordinals.merge(category, 1, Integer::sum);
            built.add(build(category, skeleton, arrangement, palette, ordinal, componentId, assetBase));
        }
        return built;
    }

    private static PosterTemplate build(PosterCategory category, Skeleton skeleton,
                                        Arrangement arrangement, Palette palette, int ordinal,
                                        Map<String, String> componentId, String assetBase) {
        Ceremony ceremony = CEREMONIES.get(category);
        String fontId = id(componentId, ceremony.font());
        String frameId = id(componentId, ceremony.frame());

        List<TextBox> boxes = skeleton.boxes().stream()
                .map(spec -> new TextBox(
                        spec.role(),
                        label(spec.role()),
                        copy(ceremony, spec.role()),
                        spec.x(), spec.y(), spec.width(), spec.height(), spec.fontSize(),
                        null,
                        shade(palette, spec.shade()),
                        spec.align()))
                .toList();

        ImageSlot slot = skeleton.slot();
        List<ImageSlot> slots = slot == null ? List.of() : List.of(new ImageSlot(
                slot.key(), slot.label(), slot.x(), slot.y(), slot.width(), slot.height(),
                slot.shape(), frameId));

        String designName = skeleton.name() + " " + arrangement.suffix;
        PosterLayout layout = new PosterLayout(
                palette.background(), null, 1080, 1350, fontId,
                boxes, slots,
                arrangement.placements(id(componentId, ceremony.motif()), id(componentId, ceremony.band())));

        return PosterTemplate.builder()
                .category(category)
                .name(palette.name() + " " + designName)
                .thumbnail("%s/thumbnails/%s-%s-%s.jpg".formatted(
                        assetBase, category.slug(), slug(designName), slug(palette.name())))
                .layout(layout)
                .colorThemes(themesAround(palette))
                .sortOrder(ordinal)
                .build();
    }

    /**
     * The template's own palette first, then the two that follow it in the list.
     *
     * <p>Three swatches rather than ten: the row is a nudge towards a colour that suits
     * the photograph, not a colour picker, and the first one is always the design as
     * drawn.
     */
    private static List<ColorTheme> themesAround(Palette palette) {
        int start = PALETTES.indexOf(palette);
        List<ColorTheme> themes = new ArrayList<>(3);
        for (int offset = 0; offset < 3; offset++) {
            Palette p = PALETTES.get((start + offset) % PALETTES.size());
            themes.add(new ColorTheme(p.name(), p.primary(), p.secondary()));
        }
        return themes;
    }

    private static String copy(Ceremony ceremony, String role) {
        return switch (role) {
            case "heading" -> ceremony.heading();
            case "names" -> ceremony.names();
            case "date" -> ceremony.date();
            case "venue" -> ceremony.venue();
            default -> ceremony.note();
        };
    }

    private static String label(String role) {
        return switch (role) {
            case "heading" -> "Heading";
            case "names" -> "Names";
            case "date" -> "Date and time";
            case "venue" -> "Venue";
            default -> "Note";
        };
    }

    private static String shade(Palette palette, String shade) {
        return switch (shade) {
            case "primary" -> palette.primary();
            case "secondary" -> palette.secondary();
            default -> palette.ink();
        };
    }

    /**
     * A missing component is a bug in this class, not bad input: the seeder creates
     * everything {@link #requiredComponentNames()} lists before generating anything.
     */
    private static String id(Map<String, String> componentId, String name) {
        String id = componentId.get(name);
        if (id == null) {
            throw new IllegalStateException("Generated catalog refers to a missing component: " + name);
        }
        return id;
    }

    private static String slug(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
