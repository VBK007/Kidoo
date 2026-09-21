package com.example.kido.media.catalog;

import java.util.Locale;
import java.util.Optional;

/**
 * What kind of thing an item is.
 *
 * <p>These map onto the client's category chips (All · Films · Anime · Series · Video
 * Songs · Ours · Music). {@code HOME_VIDEO} is "Ours" — it is a separate type rather
 * than a flag because home footage is browsed as a chronological timeline instead of a
 * poster wall, and is described by capture date, people and place rather than by year
 * and rating. {@code SERIES} and {@code VIDEO_SONG} exist as separate types for the
 * same reason {@code ANIME} does: a household that keeps a TV series or music-video
 * clips separate from its films already sorted them into their own folder, and browsing
 * wants that distinction kept rather than collapsed into "Films".
 *
 * <p>A type is assigned from the library root a file was found under, not guessed from
 * its name: nothing in a filename reliably distinguishes a film from an anime, whereas
 * a person organising a disk already sorted them into folders.
 */
public enum MediaType {

    FILM("Films", Kind.VIDEO),
    ANIME("Anime", Kind.VIDEO),
    SERIES("Series", Kind.VIDEO),
    VIDEO_SONG("Video Songs", Kind.VIDEO),
    HOME_VIDEO("Ours", Kind.VIDEO),
    MUSIC("Music", Kind.AUDIO),
    PHOTO("Photos", Kind.IMAGE),

    /**
     * Adult video, kept off every shelf that did not ask for it.
     *
     * A type rather than a flag beside one, because a type is what this library
     * already has a vocabulary for: a category is a chip, a chip is a type, and
     * the tool that moves an item between types — {@code reclassify}, in the
     * owner's fix-matches screen — works on this without being taught anything.
     * A boolean would have needed its own column, its own filter and its own
     * way of being set.
     *
     * The rule that makes it worth having is {@link #isEverydayBrowsing}: this
     * is the one video type that "All" and the home rails leave out. Somewhere
     * to put a thing is only half of it; the other half is that it stops
     * turning up between a children's film and a wedding video.
     */
    ADULT("18+", Kind.VIDEO);

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
     * Whether this type belongs in a listing nobody narrowed.
     *
     * False only for {@link #ADULT}. "All" means everything a household browses
     * together, and the whole reason for having an 18+ category is that its
     * contents should be reached deliberately rather than scrolled past — so
     * the chip that asks for it is the only thing that returns it.
     */
    public boolean isEverydayBrowsing() {
        return this != ADULT;
    }

    /** Everything "All" and the home rails cover. */
    public static java.util.List<MediaType> everydayBrowsing() {
        return java.util.Arrays.stream(values())
                .filter(MediaType::isEverydayBrowsing)
                .toList();
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
            case "series", "show", "shows", "tv" -> Optional.of(SERIES);
            case "video_song", "video_songs", "videosong", "videosongs" -> Optional.of(VIDEO_SONG);
            // "18+" does not survive a URL or a lower-cased comparison intact,
            // so the spellings a client might actually send are named here.
            case "18", "18+", "adult", "adults", "nsfw" -> Optional.of(ADULT);
            default -> Optional.empty();
        };
    }
}
