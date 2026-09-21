package com.example.kido.poster;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Where everything sits on the poster.
 *
 * <p>This is the whole design, and it is stored as one JSON document rather than as a
 * table per kind of box. A layout is only ever read and written whole — the editor
 * loads a template, the renderer draws it — so rows per text box would buy joins and
 * nothing else, and every new kind of element would be a migration.
 *
 * <p><b>Coordinates are fractions of the canvas</b>, 0.0 to 1.0, not pixels: a phone
 * preview, a tablet editor and a print export are the same design at three sizes, and
 * fractions are the only form that survives all three. {@link #canvasWidth} and
 * {@link #canvasHeight} give the aspect ratio those fractions are read against.
 *
 * @param backgroundColor    hex, behind everything
 * @param backgroundImageUrl optional, drawn over the colour and under everything else
 * @param fontComponentId    the template's default typeface — a {@link
 *                           PosterComponentType#FONT} component — which a text box may
 *                           override
 */
public record PosterLayout(

        @NotBlank
        @Pattern(regexp = "^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$",
                message = "must be a hex colour such as #F4E3C1")
        String backgroundColor,

        @Size(max = 512) String backgroundImageUrl,

        @Min(1) @Max(10000) int canvasWidth,

        @Min(1) @Max(10000) int canvasHeight,

        @Size(max = 64) String fontComponentId,

        @NotNull @Size(max = 24) List<@Valid TextBox> textBoxes,

        @NotNull @Size(max = 12) List<@Valid ImageSlot> imageSlots,

        @NotNull @Size(max = 40) List<@Valid StickerPlacement> stickers
) {

    /**
     * Normalises the nulls a client is allowed to leave out.
     *
     * <p>Called on the way in and on the way out, so nothing downstream — the
     * validator, the renderer, the phone — has to decide what an absent list means. An
     * empty list is the honest reading: a poster with no stickers.
     */
    public PosterLayout normalised() {
        return new PosterLayout(
                backgroundColor,
                backgroundImageUrl,
                canvasWidth <= 0 ? 1080 : canvasWidth,
                canvasHeight <= 0 ? 1350 : canvasHeight,
                fontComponentId,
                textBoxes == null ? List.of() : List.copyOf(textBoxes),
                imageSlots == null ? List.of() : List.copyOf(imageSlots),
                stickers == null ? List.of() : List.copyOf(stickers));
    }

    /**
     * A caption the person editing the poster types into.
     *
     * @param key        stable within the template, so a half-finished poster can be
     *                   matched back to its boxes after the template is edited
     * @param label      shown beside the field in the editor ("Bride's name")
     * @param text       what the template ships with, and what is drawn until it is
     *                   changed
     * @param fontSize   as a fraction of the canvas height, for the reason the
     *                   coordinates are fractions
     * @param fontComponentId overrides the layout's font for this box alone
     * @param align      {@code left}, {@code center} or {@code right}
     */
    public record TextBox(
            @NotBlank @Size(max = 64) String key,
            @Size(max = 120) String label,
            @Size(max = 500) String text,
            @DecimalMin("0.0") @DecimalMax("1.0") double x,
            @DecimalMin("0.0") @DecimalMax("1.0") double y,
            @DecimalMin("0.0") @DecimalMax("1.0") double width,
            @DecimalMin("0.0") @DecimalMax("1.0") double height,
            @DecimalMin("0.001") @DecimalMax("1.0") double fontSize,
            @Size(max = 64) String fontComponentId,
            @Pattern(regexp = "^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$",
                    message = "must be a hex colour such as #3B2314")
            String color,
            @Pattern(regexp = "^(left|center|right)$", message = "must be left, center or right")
            String align
    ) {}

    /**
     * A hole a photo goes into.
     *
     * @param shape            {@code rect}, {@code circle} or {@code arch}
     * @param frameComponentId an optional {@link PosterComponentType#FRAME} drawn around it
     */
    public record ImageSlot(
            @NotBlank @Size(max = 64) String key,
            @Size(max = 120) String label,
            @DecimalMin("0.0") @DecimalMax("1.0") double x,
            @DecimalMin("0.0") @DecimalMax("1.0") double y,
            @DecimalMin("0.0") @DecimalMax("1.0") double width,
            @DecimalMin("0.0") @DecimalMax("1.0") double height,
            @Pattern(regexp = "^(rect|circle|arch)$", message = "must be rect, circle or arch")
            String shape,
            @Size(max = 64) String frameComponentId
    ) {}

    /**
     * A sticker the template places for you.
     *
     * @param componentId the {@link PosterComponentType#STICKER} to draw; checked
     *                    against the component catalog when the template is saved
     * @param rotation    degrees clockwise
     */
    public record StickerPlacement(
            @NotBlank @Size(max = 64) String key,
            @NotBlank @Size(max = 64) String componentId,
            @DecimalMin("0.0") @DecimalMax("1.0") double x,
            @DecimalMin("0.0") @DecimalMax("1.0") double y,
            @DecimalMin("0.0") @DecimalMax("1.0") double width,
            @DecimalMin("0.0") @DecimalMax("1.0") double height,
            @DecimalMin("-360.0") @DecimalMax("360.0") double rotation
    ) {}
}
