package com.example.kido.poster;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;

import com.example.kido.common.ApiException;

/**
 * What a reusable piece is.
 *
 * <p>The type decides what the row has to carry, which {@code PosterComponentService}
 * enforces: a font and a sticker are files, so they need a URL, while a frame is a way
 * of drawing a border and may be nothing but properties.
 */
public enum PosterComponentType {

    /** A typeface the editor loads; {@code url} points at the woff2/ttf. */
    FONT,

    /** Decorative artwork dropped onto the canvas; {@code url} points at the image. */
    STICKER,

    /** A border drawn around a photo slot, described by {@code properties}. */
    FRAME;

    public static PosterComponentType parse(String raw) {
        if (raw != null) {
            String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (PosterComponentType type : values()) {
                if (type.name().equals(normalised)) {
                    return type;
                }
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST,
                "Unknown component type '" + raw + "'. Expected one of: " + names());
    }

    public static String names() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
