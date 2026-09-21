package com.example.kido.poster;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One tap that recolours the whole poster.
 *
 * <p>A theme is stored with the template rather than globally because the pairs that
 * work are a property of the artwork: the maroon and gold that carry a marriage card
 * look wrong on a baby shower.
 *
 * @param name      shown on the swatch ("Maroon & Gold")
 * @param primary   headings and the heavier strokes
 * @param secondary supporting text, rules and borders
 */
public record ColorTheme(
        @NotBlank @Size(max = 64) String name,

        @NotBlank
        @Pattern(regexp = "^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$",
                message = "must be a hex colour such as #7B1E3A")
        String primary,

        @NotBlank
        @Pattern(regexp = "^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$",
                message = "must be a hex colour such as #D4AF37")
        String secondary
) {}
