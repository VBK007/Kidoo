package com.example.kido.media.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One file on the home server — a film, an anime episode, home footage, a song or a
 * photograph — plus whatever the sidecar files said about it.
 *
 * <p>One table with a {@link MediaType} discriminator rather than a table per type.
 * The types share almost everything that matters here (path, size, title, artwork,
 * when it appeared) and the client browses them through a single grid with category
 * chips, so splitting them would mean unioning five queries to render one screen.
 * Type-specific fields are nullable columns: {@code artist}/{@code album} are only
 * populated for music, {@code capturedAt}/{@code place}/{@code people} only for home
 * footage and photos.
 *
 * <p>The row is keyed on {@link #filePath} because that is the only stable identity a
 * plain disk offers. {@link #fileSize} and {@link #fileModifiedAt} let a rescan skip
 * files that have not changed, which is what keeps rescanning a large disk cheap.
 */
@Entity
@Table(name = "media_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_media_item_file_path", columnNames = "file_path"),
        indexes = {
                @Index(name = "idx_media_item_sort_title", columnList = "sort_title"),
                @Index(name = "idx_media_item_type", columnList = "media_type"),
                @Index(name = "idx_media_item_missing", columnList = "missing"),
                @Index(name = "idx_media_item_captured", columnList = "captured_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaItem {

    @Id
    @UuidGenerator
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 32)
    @Builder.Default
    private MediaType type = MediaType.FILM;

    /** Name of the configured library this file was found in. */
    @Column(name = "library_name", length = 128)
    private String libraryName;

    // --- identity on disk ---

    /** Absolute path, length-bounded well above any real path to stay portable. */
    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    @Column(name = "file_name", nullable = false, length = 512)
    private String fileName;

    /** Containing directory — where sidecar artwork and {@code .nfo} files are looked up. */
    @Column(name = "folder_path", length = 1024)
    private String folderPath;

    @Column(name = "file_size")
    private long fileSize;

    @Column(name = "file_modified_at")
    private Instant fileModifiedAt;

    /**
     * Set when the file has vanished from disk. The row is kept rather than deleted so
     * watch progress survives a disk being temporarily unmounted; the catalog filters
     * these out, and the client greys them rather than hiding them.
     */
    @Builder.Default
    private boolean missing = false;

    /**
     * Set when the owner has said the metadata guess was wrong and chosen to leave the
     * item unmatched. Hidden from browsing but still on disk and still rescanned.
     */
    @Builder.Default
    private boolean hidden = false;

    // --- descriptive metadata ---

    @Column(nullable = false, length = 512)
    private String title;

    @Column(name = "original_title", length = 512)
    private String originalTitle;

    /** Lower-cased, article-stripped title used for alphabetical browsing. */
    @Column(name = "sort_title", length = 512)
    private String sortTitle;

    /**
     * Explicitly named: {@code year} is a reserved word in H2 (and a function in
     * several other engines), so the default column name fails to create the table.
     */
    @Column(name = "release_year")
    private Integer year;

    @Lob
    private String plot;

    @Column(length = 1024)
    private String tagline;

    @Column(name = "runtime_minutes")
    private Integer runtimeMinutes;

    /** 0–10, as written by whichever scraper produced the sidecar. */
    private Double rating;

    /** Age rating, e.g. {@code PG-13} or {@code U/A 13+}. */
    @Column(length = 64)
    private String certification;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "media_item_genres",
            joinColumns = @JoinColumn(name = "media_item_id"),
            indexes = @Index(name = "idx_media_item_genre", columnList = "genre"))
    @Column(name = "genre", length = 128)
    @Builder.Default
    private Set<String> genres = new LinkedHashSet<>();

    /** Display-only lists, kept denormalised since nothing queries them. */
    @Column(length = 1024)
    private String directors;

    @Lob
    private String castMembers;

    @Column(length = 512)
    private String studio;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "tmdb_id", length = 64)
    private String tmdbId;

    @Column(name = "imdb_id", length = 64)
    private String imdbId;

    /** Source label from the filename, e.g. {@code 1080p BluRay}. */
    @Column(length = 128)
    private String quality;

    @Enumerated(EnumType.STRING)
    @Column(name = "metadata_source", length = 32)
    private MetadataSource metadataSource;

    /**
     * Set when the owner has reclassified this item by hand.
     *
     * <p>Separate from {@link MetadataSource#MANUAL} because the two corrections are
     * independent: "this is a home video, not a film" says nothing about whether the
     * title is right. Without the flag a rescan would put the item straight back into
     * whatever type its library root implies.
     */
    @Column(name = "type_locked", nullable = false)
    @Builder.Default
    private boolean typeLocked = false;

    /** True when a scan may replace the descriptive fields. */
    public boolean isMetadataScannerOwned() {
        return metadataSource == null || metadataSource.isScannerOwned();
    }

    // --- music ---

    @Column(length = 512)
    private String artist;

    @Column(length = 512)
    private String album;

    @Column(name = "track_number")
    private Integer trackNumber;

    // --- home video and photos ---

    /**
     * When the footage was shot, as opposed to when the file appeared on the server.
     * Drives the home-video timeline; null is what the client's "9 clips have no date —
     * tag them?" nudge counts.
     */
    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(length = 256)
    private String place;

    /** Tagged people, used by the timeline's "By person" view. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "media_item_people",
            joinColumns = @JoinColumn(name = "media_item_id"),
            indexes = @Index(name = "idx_media_item_person", columnList = "person"))
    @Column(name = "person", length = 128)
    @Builder.Default
    private Set<String> people = new LinkedHashSet<>();

    // --- sidecar artwork (absolute paths, re-validated before every read) ---

    @Column(name = "poster_path", length = 1024)
    private String posterPath;

    @Column(name = "backdrop_path", length = 1024)
    private String backdropPath;

    // --- probe cache ---

    @Embedded
    @Builder.Default
    private MediaInfo mediaInfo = new MediaInfo();

    // --- playback statistics, for the admin panel ---

    /**
     * How often this file has been served as-is versus re-encoded.
     *
     * <p>Counted rather than inferred, because "this title always transcodes" is only
     * knowable from history: the same file direct-plays to one device and not another,
     * so its codecs alone do not say whether it is a problem in practice.
     */
    @Column(name = "direct_play_count", nullable = false)
    @Builder.Default
    private long directPlayCount = 0;

    @Column(name = "transcode_count", nullable = false)
    @Builder.Default
    private long transcodeCount = 0;

    @Column(name = "last_played_at")
    private Instant lastPlayedAt;

    /**
     * True for a file that has been played and has never once direct-played — the
     * admin panel's "always transcodes" note, and the strongest candidate for
     * re-encoding once so it stops costing CPU on every view.
     */
    public boolean alwaysTranscodes() {
        return transcodeCount > 0 && directPlayCount == 0;
    }

    public long playCount() {
        return directPlayCount + transcodeCount;
    }

    // --- bookkeeping ---

    @Column(name = "added_at")
    @Builder.Default
    private Instant addedAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    public boolean hasPoster() {
        return posterPath != null && !posterPath.isBlank();
    }

    public boolean hasBackdrop() {
        return backdropPath != null && !backdropPath.isBlank();
    }

    /** Browsable means indexed, present on disk and not deliberately hidden. */
    public boolean isBrowsable() {
        return !missing && !hidden;
    }
}
