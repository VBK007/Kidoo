package com.example.kido.poster;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.poster.PosterLayout.ImageSlot;
import com.example.kido.poster.PosterLayout.StickerPlacement;
import com.example.kido.poster.PosterLayout.TextBox;

import lombok.extern.slf4j.Slf4j;

/**
 * Seeds one template per ceremony so the module answers with something the first time
 * it is asked.
 *
 * <p>Seeded rather than migrated: a template is content, and a later version should be
 * free to redraw the marriage card without a data migration chasing every household.
 * It runs only when there is not a single template in the database, so an operator who
 * deletes a sample gets it deleted, not restored on the next restart.
 *
 * <p>The artwork these point at is <b>not hosted by this server</b>.
 * {@code app.poster.asset-base-url} is where the fonts and stickers live; point it at
 * your own bucket, or turn the seeding off with {@code app.poster.seed-samples=false}
 * and publish your own templates through the API.
 */
@Slf4j
@Component
public class PosterSampleData implements ApplicationRunner {

    private final PosterTemplateRepository templates;
    private final PosterComponentRepository components;
    private final boolean enabled;
    private final String assetBase;

    public PosterSampleData(PosterTemplateRepository templates,
                            PosterComponentRepository components,
                            @Value("${app.poster.seed-samples:true}") boolean enabled,
                            @Value("${app.poster.asset-base-url:https://assets.kiduu.app/poster}") String assetBase) {
        this.templates = templates;
        this.components = components;
        this.enabled = enabled;
        this.assetBase = assetBase.endsWith("/") ? assetBase.substring(0, assetBase.length() - 1) : assetBase;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || templates.count() > 0) {
            return;
        }

        String vibes = font("Great Vibes", "fonts/great-vibes.woff2");
        String baloo = font("Baloo 2", "fonts/baloo-2.woff2");
        String quicksand = font("Quicksand", "fonts/quicksand.woff2");

        String mandala = sticker("Gold Mandala Corner", "stickers/gold-mandala-corner.png");
        String garland = sticker("Marigold Garland", "stickers/marigold-garland.png");
        String balloons = sticker("Balloon Cluster", "stickers/balloon-cluster.png");
        String confetti = sticker("Confetti Burst", "stickers/confetti-burst.png");
        String pram = sticker("Baby Pram", "stickers/baby-pram.png");
        String cloud = sticker("Pastel Cloud", "stickers/pastel-cloud.png");

        String goldRing = frame("Gold Beaded Ring", Map.of(
                "borderColor", "#D4AF37", "borderWidth", 6, "style", "beaded"));
        String pastelRounded = frame("Pastel Rounded", Map.of(
                "borderColor", "#A8D5E5", "borderWidth", 4, "cornerRadius", 24, "style", "solid"));

        templates.save(marriage(vibes, mandala, garland, goldRing));
        templates.save(birthday(baloo, balloons, confetti, pastelRounded));
        templates.save(babyShower(quicksand, pram, cloud, pastelRounded));

        log.info("Seeded {} sample poster templates and {} components",
                templates.count(), components.count());
    }

    // --- the three samples -------------------------------------------------------

    private PosterTemplate marriage(String font, String mandala, String garland, String frame) {
        PosterLayout layout = new PosterLayout(
                "#FDF6EC", null, 1080, 1350, font,
                List.of(
                        new TextBox("heading", "Heading", "Wedding Invitation",
                                0.1, 0.08, 0.8, 0.07, 0.035, null, "#7B1E3A", "center"),
                        new TextBox("couple", "Couple's names", "Aarav  &  Diya",
                                0.08, 0.17, 0.84, 0.12, 0.075, null, "#7B1E3A", "center"),
                        new TextBox("date", "Date and time", "Sunday, 14 February 2027  ·  7:30 pm",
                                0.1, 0.62, 0.8, 0.05, 0.028, null, "#3B2314", "center"),
                        new TextBox("venue", "Venue", "Kalyana Mandapam, Coimbatore",
                                0.1, 0.69, 0.8, 0.06, 0.026, null, "#3B2314", "center"),
                        new TextBox("blessing", "Blessing", "Together with their families",
                                0.12, 0.78, 0.76, 0.05, 0.022, null, "#8A6A4F", "center")),
                List.of(new ImageSlot("couple-photo", "Couple's photograph",
                        0.28, 0.31, 0.44, 0.27, "circle", frame)),
                List.of(
                        new StickerPlacement("corner-top-left", mandala, 0.02, 0.02, 0.22, 0.18, 0),
                        new StickerPlacement("corner-bottom-right", mandala, 0.76, 0.8, 0.22, 0.18, 180),
                        new StickerPlacement("garland", garland, 0.1, 0.88, 0.8, 0.1, 0)));

        return PosterTemplate.builder()
                .category(PosterCategory.MARRIAGE)
                .name("Maroon & Gold Mandala")
                .thumbnail(assetBase + "/thumbnails/marriage-maroon-gold.jpg")
                .layout(layout)
                .colorThemes(List.of(
                        new ColorTheme("Maroon & Gold", "#7B1E3A", "#D4AF37"),
                        new ColorTheme("Ivory & Rose", "#B5838D", "#F4E3C1"),
                        new ColorTheme("Emerald & Gold", "#1E5945", "#D4AF37")))
                .sortOrder(0)
                .build();
    }

    private PosterTemplate birthday(String font, String balloons, String confetti, String frame) {
        PosterLayout layout = new PosterLayout(
                "#FFF7E8", null, 1080, 1350, font,
                List.of(
                        new TextBox("heading", "Heading", "It's my birthday!",
                                0.08, 0.07, 0.84, 0.07, 0.038, null, "#F4763B", "center"),
                        new TextBox("name", "Birthday child", "Meera",
                                0.08, 0.16, 0.84, 0.11, 0.08, null, "#F4763B", "center"),
                        new TextBox("age", "Turning", "turns 5",
                                0.08, 0.27, 0.84, 0.06, 0.036, null, "#2F7F8F", "center"),
                        new TextBox("date", "Date and time", "Saturday, 9 May  ·  4 pm",
                                0.1, 0.63, 0.8, 0.05, 0.03, null, "#3B2314", "center"),
                        new TextBox("venue", "Venue", "Flat 402, Lake View Apartments",
                                0.1, 0.7, 0.8, 0.06, 0.026, null, "#3B2314", "center")),
                List.of(new ImageSlot("child-photo", "Photograph",
                        0.25, 0.34, 0.5, 0.26, "rect", frame)),
                List.of(
                        new StickerPlacement("balloons", balloons, 0.02, 0.04, 0.24, 0.22, -8),
                        new StickerPlacement("confetti", confetti, 0.72, 0.03, 0.26, 0.22, 12),
                        new StickerPlacement("confetti-foot", confetti, 0.04, 0.82, 0.24, 0.16, 190)));

        return PosterTemplate.builder()
                .category(PosterCategory.BIRTHDAY)
                .name("Confetti Pop")
                .thumbnail(assetBase + "/thumbnails/birthday-confetti-pop.jpg")
                .layout(layout)
                .colorThemes(List.of(
                        new ColorTheme("Sunshine", "#F4763B", "#FFD166"),
                        new ColorTheme("Bubblegum", "#E5566D", "#FFC2D1"),
                        new ColorTheme("Deep Sea", "#2F7F8F", "#9AD1D4")))
                .sortOrder(0)
                .build();
    }

    private PosterTemplate babyShower(String font, String pram, String cloud, String frame) {
        PosterLayout layout = new PosterLayout(
                "#EAF4FB", null, 1080, 1350, font,
                List.of(
                        new TextBox("heading", "Heading", "Baby Shower",
                                0.08, 0.09, 0.84, 0.08, 0.05, null, "#5B7DB1", "center"),
                        new TextBox("parents", "Parents-to-be", "for Nithya & Karthik",
                                0.08, 0.19, 0.84, 0.06, 0.032, null, "#5B7DB1", "center"),
                        new TextBox("date", "Date and time", "Sunday, 2 August  ·  11 am",
                                0.1, 0.64, 0.8, 0.05, 0.03, null, "#41566F", "center"),
                        new TextBox("venue", "Venue", "Rose Hall, Race Course Road",
                                0.1, 0.71, 0.8, 0.06, 0.026, null, "#41566F", "center"),
                        new TextBox("note", "Note", "Your blessings are the only gift we need",
                                0.12, 0.8, 0.76, 0.06, 0.022, null, "#7C8FA6", "center")),
                List.of(new ImageSlot("scan-photo", "Photograph",
                        0.29, 0.31, 0.42, 0.29, "arch", frame)),
                List.of(
                        new StickerPlacement("pram", pram, 0.04, 0.83, 0.22, 0.15, 0),
                        new StickerPlacement("cloud-left", cloud, 0.03, 0.05, 0.22, 0.12, 0),
                        new StickerPlacement("cloud-right", cloud, 0.74, 0.12, 0.24, 0.13, 0)));

        return PosterTemplate.builder()
                .category(PosterCategory.BABY_SHOWER)
                .name("Pastel Clouds")
                .thumbnail(assetBase + "/thumbnails/baby-shower-pastel-clouds.jpg")
                .layout(layout)
                .colorThemes(List.of(
                        new ColorTheme("Sky", "#5B7DB1", "#C7E0F4"),
                        new ColorTheme("Blush", "#C98A9B", "#F6DDE3"),
                        new ColorTheme("Mint", "#4E9C81", "#CDEBDF")))
                .sortOrder(0)
                .build();
    }

    // --- components, by name so a re-run reuses rather than duplicates -----------

    private String font(String name, String path) {
        return component(PosterComponentType.FONT, name, assetBase + "/" + path, Map.of());
    }

    private String sticker(String name, String path) {
        return component(PosterComponentType.STICKER, name, assetBase + "/" + path, Map.of());
    }

    private String frame(String name, Map<String, Object> properties) {
        return component(PosterComponentType.FRAME, name, null, properties);
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
