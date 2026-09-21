package com.example.kido.poster;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * Fills an empty catalog, so the module answers with something the first time it is
 * asked.
 *
 * <p>Seeded rather than migrated: templates are content, and a later version should be
 * free to redraw them without a data migration chasing every household. It runs only
 * when there is not a single template in the database, so an operator who deletes one
 * gets it deleted, not restored on the next restart.
 *
 * <p>The volume comes from {@link PosterCatalogGenerator} — {@code app.poster.seed-count}
 * templates spread evenly over the seven ceremonies. A picker with four marriage cards
 * in it looks broken, and a thousand hand-written rows would be a thousand chances to
 * typo a hex colour.
 *
 * <p>The artwork these point at is <b>not hosted by this server</b>.
 * {@code app.poster.asset-base-url} is where the fonts and stickers live; point it at
 * your own bucket, or turn the seeding off with {@code app.poster.seed-samples=false}
 * and publish your own templates through the API.
 */
@Slf4j
@Component
public class PosterSampleData implements ApplicationRunner {

    /** Inserts per flush. Large enough to matter, small enough not to hold 1000 rows. */
    private static final int BATCH = 200;

    private final PosterTemplateRepository templates;
    private final PosterComponentRepository components;
    private final boolean enabled;
    private final int seedCount;
    private final String assetBase;

    public PosterSampleData(PosterTemplateRepository templates,
                            PosterComponentRepository components,
                            @Value("${app.poster.seed-samples:true}") boolean enabled,
                            @Value("${app.poster.seed-count:1000}") int seedCount,
                            @Value("${app.poster.asset-base-url:https://assets.kiduu.app/poster}") String assetBase) {
        this.templates = templates;
        this.components = components;
        this.enabled = enabled;
        this.seedCount = seedCount;
        this.assetBase = assetBase.endsWith("/") ? assetBase.substring(0, assetBase.length() - 1) : assetBase;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || seedCount <= 0 || templates.count() > 0) {
            return;
        }
        Instant started = Instant.now();
        Map<String, String> componentIds = ensureComponents();

        List<PosterTemplate> generated =
                PosterCatalogGenerator.generate(seedCount, componentIds, assetBase);
        for (int from = 0; from < generated.size(); from += BATCH) {
            templates.saveAll(generated.subList(from, Math.min(from + BATCH, generated.size())));
        }

        if (seedCount > PosterCatalogGenerator.DISTINCT_COMBINATIONS) {
            log.warn("app.poster.seed-count is {}, above the {} distinct designs available — "
                            + "the extras repeat earlier designs under new names",
                    seedCount, PosterCatalogGenerator.DISTINCT_COMBINATIONS);
        }
        log.info("Seeded {} poster templates and {} components in {} ms",
                generated.size(), componentIds.size(),
                Duration.between(started, Instant.now()).toMillis());
    }

    /**
     * The fonts, stickers and frames the generated designs point at, by name.
     *
     * <p>Looked up before being created, so a re-seed of a catalog whose components
     * survived reuses those rows and their ids rather than colliding with the unique
     * constraint on (type, name).
     */
    private Map<String, String> ensureComponents() {
        Map<String, String> ids = new LinkedHashMap<>();

        // Fonts: the editor loads these by URL.
        font(ids, "Great Vibes", "fonts/great-vibes.ttf");
        font(ids, "Pacifico", "fonts/pacifico.ttf");
        font(ids, "Comic Sans MS", "fonts/comic-sans-ms.ttf");
        font(ids, "Baloo 2", "fonts/baloo-2.ttf");
        font(ids, "Quicksand", "fonts/quicksand.ttf");

        // Stickers: a corner motif and a foot band for each ceremony.
        sticker(ids, "Wedding Ring", "stickers/ring.png");
        sticker(ids, "Gold Mandala Corner", "stickers/gold-mandala-corner.png");
        sticker(ids, "Marigold Garland", "stickers/marigold-garland.png");
        sticker(ids, "Balloon", "stickers/balloon.png");
        sticker(ids, "Confetti Burst", "stickers/confetti-burst.png");
        sticker(ids, "Baby Icon", "stickers/baby.png");
        sticker(ids, "Baby Pram", "stickers/baby-pram.png");
        sticker(ids, "Pastel Cloud", "stickers/pastel-cloud.png");
        sticker(ids, "Diya Lamp", "stickers/diya-lamp.png");
        sticker(ids, "Champagne Toast", "stickers/champagne-toast.png");

        // Frames: no file, only the border the renderer strokes around a photo slot.
        frame(ids, "Circle Maroon", Map.of(
                "shape", "circle", "borderColor", "#880E4F", "borderWidth", 4));
        frame(ids, "Rectangle Amber", Map.of(
                "shape", "rectangle", "borderColor", "#F57F17", "borderWidth", 3));
        frame(ids, "Rounded Blue", Map.of(
                "shape", "rounded-rectangle", "borderColor", "#1565C0", "borderWidth", 2,
                "cornerRadius", 24));
        frame(ids, "Gold Beaded Ring", Map.of(
                "shape", "circle", "borderColor", "#D4AF37", "borderWidth", 6, "style", "beaded"));
        frame(ids, "Pastel Rounded", Map.of(
                "shape", "rounded-rectangle", "borderColor", "#A8D5E5", "borderWidth", 4,
                "cornerRadius", 24));

        List<String> missing = PosterCatalogGenerator.requiredComponentNames().stream()
                .filter(name -> !ids.containsKey(name))
                .toList();
        if (!missing.isEmpty()) {
            // Only reachable by editing one list and not the other.
            throw new IllegalStateException("Seed components missing for the generated catalog: " + missing);
        }
        return ids;
    }

    private void font(Map<String, String> ids, String name, String path) {
        ids.put(name, component(PosterComponentType.FONT, name, assetBase + "/" + path, Map.of()));
    }

    private void sticker(Map<String, String> ids, String name, String path) {
        ids.put(name, component(PosterComponentType.STICKER, name, assetBase + "/" + path, Map.of()));
    }

    private void frame(Map<String, String> ids, String name, Map<String, Object> properties) {
        ids.put(name, component(PosterComponentType.FRAME, name, null, properties));
    }

    private String component(PosterComponentType type, String name, String url, Map<String, Object> properties) {
        return components.findByTypeAndName(type, name)
                .orElseGet(() -> components.save(PosterComponent.builder()
                        .type(type)
                        .name(name)
                        .url(url)
                        .properties(new LinkedHashMap<>(properties))
                        .build()))
                .getId();
    }
}
