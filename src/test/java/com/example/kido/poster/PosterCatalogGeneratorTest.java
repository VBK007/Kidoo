package com.example.kido.poster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The generated catalog, at the size it actually ships at, without a database.
 *
 * <p>Worth testing on its own because the seeder writes it once into an empty
 * installation and then never looks again: a design with a coordinate off the canvas
 * or a reference to a component nobody created would sit in a thousand rows until
 * somebody opened the wrong template.
 */
class PosterCatalogGeneratorTest {

    private static final Pattern HEX = Pattern.compile("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");

    /** Stands in for the seeder's component table: every required name gets an id. */
    private static Map<String, String> componentIds() {
        Map<String, String> ids = new HashMap<>();
        PosterCatalogGenerator.requiredComponentNames()
                .forEach(name -> ids.put(name, "id-" + name.toLowerCase().replace(' ', '-')));
        return ids;
    }

    @Test
    void a_thousand_templates_are_spread_over_every_ceremony_and_named_apart() {
        List<PosterTemplate> catalog = PosterCatalogGenerator.generate(1000, componentIds(), "https://cdn.test");

        assertEquals(1000, catalog.size());

        Map<PosterCategory, Long> perCategory = catalog.stream()
                .collect(Collectors.groupingBy(PosterTemplate::getCategory, Collectors.counting()));
        assertEquals(PosterCategory.values().length, perCategory.size(),
                "every ceremony should get templates: " + perCategory);
        // 1000 over 7 ceremonies divides unevenly; nobody should be short by more than one.
        assertTrue(perCategory.values().stream().max(Long::compare).orElseThrow()
                        - perCategory.values().stream().min(Long::compare).orElseThrow() <= 1,
                "ceremonies should be within one of each other: " + perCategory);

        // A name is the design and the palette — "Royal Gold Classic Scroll Clean" —
        // so the same one turns up under two ceremonies, which the category
        // distinguishes. What matters is that a shelf is not twenty rows of one name.
        Set<String> names = catalog.stream().map(PosterTemplate::getName).collect(Collectors.toSet());
        assertTrue(names.size() > 100, "names should vary across designs and palettes, got " + names.size());

        // Unique within a ceremony, which is the list anybody actually reads.
        for (PosterCategory category : PosterCategory.values()) {
            List<String> inCategory = catalog.stream()
                    .filter(t -> t.getCategory() == category)
                    .map(t -> t.getName() + "#" + t.getSortOrder())
                    .toList();
            assertEquals(inCategory.size(), new HashSet<>(inCategory).size(),
                    category + " has two templates with the same name and order");
        }
    }

    @Test
    void every_generated_layout_is_drawable() {
        Map<String, String> ids = componentIds();
        Set<String> known = new HashSet<>(ids.values());

        for (PosterTemplate template : PosterCatalogGenerator.generate(1000, ids, "https://cdn.test")) {
            PosterLayout layout = template.getLayout();
            String where = template.getCategory() + " / " + template.getName();

            assertTrue(HEX.matcher(layout.backgroundColor()).matches(), where + " background");
            assertTrue(layout.canvasWidth() > 0 && layout.canvasHeight() > 0, where + " canvas");
            assertTrue(known.contains(layout.fontComponentId()), where + " font reference");
            assertFalse(layout.textBoxes().isEmpty(), where + " has no text at all");

            for (PosterLayout.TextBox box : layout.textBoxes()) {
                assertTrue(box.key() != null && !box.key().isBlank(), where + " text box key");
                assertTrue(box.text() != null && !box.text().isBlank(), where + " text box copy");
                assertTrue(HEX.matcher(box.color()).matches(), where + " text colour " + box.color());
                assertWithinCanvas(where + " text box " + box.key(), box.x(), box.y(), box.width(), box.height());
                assertTrue(box.fontSize() > 0 && box.fontSize() <= 1.0, where + " font size");
            }
            for (PosterLayout.ImageSlot slot : layout.imageSlots()) {
                assertWithinCanvas(where + " slot " + slot.key(), slot.x(), slot.y(), slot.width(), slot.height());
                assertTrue(known.contains(slot.frameComponentId()), where + " frame reference");
            }
            for (PosterLayout.StickerPlacement sticker : layout.stickers()) {
                assertWithinCanvas(where + " sticker " + sticker.key(),
                        sticker.x(), sticker.y(), sticker.width(), sticker.height());
                assertTrue(known.contains(sticker.componentId()), where + " sticker reference");
            }

            assertEquals(3, template.getColorThemes().size(), where + " should offer three palettes");
            template.getColorThemes().forEach(theme -> {
                assertTrue(HEX.matcher(theme.primary()).matches(), where + " theme primary");
                assertTrue(HEX.matcher(theme.secondary()).matches(), where + " theme secondary");
            });
            assertTrue(template.getThumbnail().startsWith("https://cdn.test/thumbnails/"), where + " thumbnail");
            assertTrue(template.isPublished(), where + " should be published");
        }
    }

    @Test
    void the_same_count_always_builds_the_same_catalog() {
        Map<String, String> ids = componentIds();
        List<PosterTemplate> first = PosterCatalogGenerator.generate(60, ids, "https://cdn.test");
        List<PosterTemplate> second = PosterCatalogGenerator.generate(60, ids, "https://cdn.test");

        assertEquals(names(first), names(second));
        // And a longer run is the shorter one plus more, rather than a reshuffle — so
        // raising seed-count on an existing install adds designs instead of renaming them.
        assertEquals(names(first), names(PosterCatalogGenerator.generate(120, ids, "https://cdn.test")).subList(0, 60));
    }

    @Test
    void the_first_handful_already_covers_every_ceremony() {
        // The picker's first screen should not be seven marriage cards.
        List<PosterTemplate> catalog = PosterCatalogGenerator.generate(7, componentIds(), "https://cdn.test");
        assertEquals(PosterCategory.values().length,
                catalog.stream().map(PosterTemplate::getCategory).distinct().count());
    }

    private static List<String> names(List<PosterTemplate> catalog) {
        return catalog.stream().map(t -> t.getCategory() + "/" + t.getName()).toList();
    }

    private static void assertWithinCanvas(String where, double x, double y, double width, double height) {
        assertTrue(x >= 0 && x <= 1, where + " x=" + x);
        assertTrue(y >= 0 && y <= 1, where + " y=" + y);
        assertTrue(width > 0 && width <= 1, where + " width=" + width);
        assertTrue(height > 0 && height <= 1, where + " height=" + height);
        assertTrue(x + width <= 1.0001, where + " runs off the right edge");
        assertTrue(y + height <= 1.0001, where + " runs off the bottom edge");
    }
}
