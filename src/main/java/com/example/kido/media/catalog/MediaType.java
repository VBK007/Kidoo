package com.example.kido.media.catalog;

import java.util.Locale;
import java.util.Optional;

/**
 * What kind of thing an item is.
 *
 * <p>The five values map onto the client's category chips (All · Films · Anime · Ours ·
 * Music). {@code HOME_VIDEO} is "Ours" — it is a separate type rather than a flag
 * because home footage is browsed as a chronological timeline instead of a poster wall,
 * and is described by capture date, people and place rather than by year and rating.
 *
 * <p>A type is assigned from the library root a file was found under, not guessed from
 * its name: nothing in a filename reliably distinguishes a film from an anime, whereas
 * a person organising a disk already sorted them into folders.
 */
public enum MediaType {

    FILM("Films", Kind.VIDEO),
    ANIME("Anime", Kind.VIDEO),
    HOME_VIDEO("Ours", Kind.VIDEO),
    MUSIC("Music", Kind.AUDIO),
    PHOTO("Photos", Kind.IMAGE);

    /** Broad grouping that decides how a file is handled rather than how it is shown. */
    public enum Kind {
        VIDEO, AUDIO, IMAGE
    }

    private final String label;
    private final Kind kind;

    MediaType(String label, Kind kind) {
        this.label = label;
        this.kind = kind;
    }

    /** Client-facing chip label. */
    public String label() {
        return label;
    }

    public Kind kind() {
        return kind;
    }

    /** Only video is worth probing for codecs, trickplay frames and chapters. */
    public boolean isVideo() {
        return kind == Kind.VIDEO;
    }

    /** True for the types shown on the home-video timeline. */
    public boolean isTimeline() {
        return this == HOME_VIDEO || this == PHOTO;
    }

    /**
     * Parses a chip value, accepting the enum name or the label ({@code ours},
     * {@code home_video} and {@code Ours} all resolve).
     */
    public static Optional<MediaType> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (MediaType type : values()) {
            if (type.name().toLowerCase(Locale.ROOT).equals(value)
                    || type.label.toLowerCase(Locale.ROOT).equals(value)) {
                return Optional.of(type);
            }
        }
        // Singular forms, since the chips are plural but an API caller may not be.
        return switch (value) {
            case "film", "movie", "movies" -> Optional.of(FILM);
            case "photo", "photos", "camera_roll" -> Optional.of(PHOTO);
            case "song", "songs", "audio" -> Optional.of(MUSIC);
            case "home", "home_videos", "our" -> Optional.of(HOME_VIDEO);
            default -> Optional.empty();
        };
    }
}
