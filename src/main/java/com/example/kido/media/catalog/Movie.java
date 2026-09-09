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
 * One movie file on the home server, plus whatever the sidecar files said about it.
 *
 * <p>The row is keyed on {@link #filePath} because that is the only stable identity a
 * plain disk offers. {@link #fileSize} and {@link #fileModifiedAt} let a rescan skip
 * files that have not changed, which is what keeps a scan of a large library cheap.
 */
@Entity
@Table(name = "movies",
        uniqueConstraints = @UniqueConstraint(name = "uk_movie_file_path", columnNames = "file_path"),
        indexes = {
                @Index(name = "idx_movie_sort_title", columnList = "sort_title"),
                @Index(name = "idx_movie_missing", columnList = "missing")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Movie {

    @Id
    @UuidGenerator
    private String id;

    // --- identity on disk ---

    /** Absolute path. Length-bounded well above any real path to stay portable across DBs. */
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
     * that watch progress survives a disk being temporarily unmounted; the catalog
     * filters these out.
     */
    @Builder.Default
    private boolean missing = false;

    // --- descriptive metadata ---

    @Column(nullable = false, length = 512)
    private String title;

    @Column(name = "original_title", length = 512)
    private String originalTitle;

    /** Lower-cased, article-stripped title used for alphabetical browsing. */
    @Column(name = "sort_title", length = 512)
    private String sortTitle;

    /**
     * Explicitly named: {@code year} is a reserved word in H2 (and a function in several
     * other engines), so the default column name fails to create the table.
     */
    @Column(name = "release_year")
    private Integer year;

    @Lob
    private String plot;

    @Column(length = 1024)
    private String tagline;

    @Column(name = "runtime_minutes")
    private Integer runtimeMinutes;

    /** 0–10, as written by the scraper that produced the {@code .nfo}. */
    private Double rating;

    /** Age rating, e.g. {@code PG-13} or {@code U/A 13+}. */
    @Column(length = 64)
    private String certification;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "movie_genres",
            joinColumns = @JoinColumn(name = "movie_id"),
            indexes = @Index(name = "idx_movie_genre", columnList = "genre"))
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

    // --- sidecar artwork (absolute paths, re-validated before every read) ---

    @Column(name = "poster_path", length = 1024)
    private String posterPath;

    @Column(name = "backdrop_path", length = 1024)
    private String backdropPath;

    // --- probe cache ---

    @Embedded
    @Builder.Default
    private MediaInfo mediaInfo = new MediaInfo();

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
}
