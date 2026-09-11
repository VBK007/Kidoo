package com.example.kido.media.admin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.example.kido.common.ApiException;
import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.catalog.MediaItemRepository;
import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.catalog.MetadataSource;
import com.example.kido.media.dto.MovieAdminDtos.MovieAdminDto;
import com.example.kido.media.dto.MovieAdminDtos.MovieUpsertRequest;
import com.example.kido.media.engagement.CommentService;
import com.example.kido.media.metadata.FilenameParser;

import lombok.extern.slf4j.Slf4j;

/**
 * Creating and editing a title by hand, thumbnail included.
 *
 * <p>One entry point for both, because insert and update differ by exactly one field.
 * What they share is the rule that a hand-made edit outranks the scanner: everything
 * written here is stamped {@link MetadataSource#MANUAL} and the type is locked, so the
 * next scan reads the file again without undoing what somebody typed.
 *
 * <p>Absent and empty are held apart throughout. A null field is untouched, so a form
 * that edits one thing sends one thing; an empty string or empty list clears it. The
 * alternative — treating absent as empty — turns every partial form into a wipe.
 */
@Slf4j
@Service
public class MovieAdminService {

    /** What a browser will actually display, and all that artwork is ever stored as. */
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/jpg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif");

    private final MediaItemRepository items;
    private final MediaPaths paths;
    private final CommentService comments;
    private final long maxArtworkBytes;

    public MovieAdminService(MediaItemRepository items,
                             MediaPaths paths,
                             CommentService comments,
                             MediaProperties props) {
        this.items = items;
        this.paths = paths;
        this.comments = comments;
        this.maxArtworkBytes = (long) props.getArtworkMaxSizeMb() * 1024 * 1024;
    }

    @Transactional
    public MovieAdminDto upsert(MovieUpsertRequest req, MultipartFile poster,
                                MultipartFile backdrop) {
        boolean creating = req.id() == null || req.id().isBlank();
        MediaItem item = creating ? newItem(req) : existing(req.id());

        applyMetadata(item, req, creating);

        // Stamped on every hand-made write: the scanner reads the file again on its next
        // pass, and without this it would put its own guess back over the typed answer.
        item.setMetadataSource(MetadataSource.MANUAL);
        item.setTypeLocked(true);
        item.setUpdatedAt(Instant.now());

        MediaItem saved = items.save(item);

        // After the save, so artwork is filed under an id that exists. A new row has no
        // id until then, and naming the files after a temporary one would orphan them.
        boolean artworkChanged = false;
        if (isPresent(poster)) {
            saved.setPosterPath(storeArtwork(saved.getId(), "poster", poster));
            artworkChanged = true;
        }
        if (isPresent(backdrop)) {
            saved.setBackdropPath(storeArtwork(saved.getId(), "backdrop", backdrop));
            artworkChanged = true;
        }
        if (artworkChanged) {
            saved = items.save(saved);
        }

        log.info("{} title id={} title='{}'", creating ? "Created" : "Updated",
                saved.getId(), saved.getTitle());
        return toDto(saved, creating);
    }

    // --- the row ----------------------------------------------------------

    private MediaItem newItem(MovieUpsertRequest req) {
        if (isBlank(req.title())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A new title needs a title");
        }
        MediaType type = req.type() == null ? MediaType.FILM : parseType(req.type());

        MediaItem item = MediaItem.builder()
                .type(type)
                .libraryName(req.libraryName() == null ? type.label() : req.libraryName().trim())
                .build();

        if (isBlank(req.filePath())) {
            // Nothing on disk to point at, so the row is marked as catalogued rather
            // than given a path that resolves to nothing. The synthetic value only
            // satisfies the unique-and-not-null column; it is never opened.
            item.setFilePath("catalog:" + UUID.randomUUID());
            item.setFileName(req.title().trim());
            item.setCatalogOnly(true);
        } else {
            attachFile(item, req.filePath());
        }
        return item;
    }

    private MediaItem existing(String id) {
        return items.findById(id).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "Item not found"));
    }

    /**
     * Points a row at a real video.
     *
     * <p>The path goes through the same gate as every other filesystem access, so a
     * caller cannot catalogue {@code /etc/passwd} and then stream it: it has to exist
     * and to sit inside a configured library.
     */
    private void attachFile(MediaItem item, String rawPath) {
        Path file = paths.requireWithinRoots(rawPath);
        item.setFilePath(file.toString());
        item.setFileName(file.getFileName().toString());
        item.setFolderPath(file.getParent() == null ? null : file.getParent().toString());
        item.setCatalogOnly(false);
        item.setMissing(false);
        try {
            item.setFileSize(Files.size(file));
            item.setFileModifiedAt(Files.getLastModifiedTime(file).toInstant());
        } catch (IOException ex) {
            log.warn("Could not read file attributes for {}: {}", file, ex.toString());
        }
        paths.libraryOf(file).ifPresent(root -> {
            item.setLibraryName(root.name());
            if (!item.isTypeLocked()) {
                item.setType(root.type());
            }
        });
    }

    private void applyMetadata(MediaItem item, MovieUpsertRequest req, boolean creating) {
        if (req.type() != null && !creating) {
            item.setType(parseType(req.type()));
        }
        if (req.libraryName() != null) {
            item.setLibraryName(blankToNull(req.libraryName()));
        }
        if (req.filePath() != null && !creating) {
            attachFile(item, req.filePath());
        }

        if (req.title() != null) {
            if (isBlank(req.title())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "A title cannot be cleared");
            }
            item.setTitle(req.title().trim());
            item.setSortTitle(FilenameParser.sortTitle(item.getTitle()));
        }
        // Set after the title, so an explicit sort title is not overwritten by the one
        // derived from it when both arrive in the same call.
        if (req.sortTitle() != null) {
            item.setSortTitle(isBlank(req.sortTitle())
                    ? FilenameParser.sortTitle(item.getTitle())
                    : req.sortTitle().trim().toLowerCase(Locale.ROOT));
        }

        if (req.originalTitle() != null) {
            item.setOriginalTitle(blankToNull(req.originalTitle()));
        }
        if (req.year() != null) {
            item.setYear(req.year());
        }
        if (req.plot() != null) {
            item.setPlot(blankToNull(req.plot()));
        }
        if (req.tagline() != null) {
            item.setTagline(blankToNull(req.tagline()));
        }
        if (req.runtimeMinutes() != null) {
            item.setRuntimeMinutes(req.runtimeMinutes());
        }
        if (req.rating() != null) {
            item.setRating(req.rating());
        }
        if (req.certification() != null) {
            item.setCertification(blankToNull(req.certification()));
        }
        if (req.studio() != null) {
            item.setStudio(blankToNull(req.studio()));
        }
        if (req.tmdbId() != null) {
            item.setTmdbId(blankToNull(req.tmdbId()));
        }
        if (req.imdbId() != null) {
            item.setImdbId(blankToNull(req.imdbId()));
        }
        if (req.quality() != null) {
            item.setQuality(blankToNull(req.quality()));
        }
        if (req.releaseDate() != null) {
            item.setReleaseDate(parseDate(req.releaseDate()));
        }
        if (req.genres() != null) {
            item.setGenres(cleanSet(req.genres()));
        }
        if (req.directors() != null) {
            item.setDirectors(join(req.directors()));
        }
        if (req.actors() != null) {
            item.setCastMembers(join(req.actors()));
        }
    }

    // --- artwork ----------------------------------------------------------

    /**
     * Writes one uploaded image and returns where it went.
     *
     * <p>The name on disk is built from the item id and the kind, never from the name
     * the client sent: an upload called {@code ../../config.jpg} has to land in the
     * artwork directory like any other. Replacing a poster clears the other extensions
     * first, so a PNG swapped for a JPEG does not leave the old file behind for the
     * next lookup to find.
     */
    private String storeArtwork(String itemId, String kind, MultipartFile upload) {
        String extension = IMAGE_TYPES.get(contentTypeOf(upload));
        if (extension == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Artwork must be a JPEG, PNG, WebP or GIF image");
        }
        if (upload.getSize() > maxArtworkBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Artwork must be smaller than " + (maxArtworkBytes / 1024 / 1024) + "MB");
        }

        Path dir = paths.artworkDir().resolve(itemId);
        try {
            Files.createDirectories(dir);
            for (String known : Set.copyOf(IMAGE_TYPES.values())) {
                Files.deleteIfExists(dir.resolve(kind + "." + known));
            }
            Path target = dir.resolve(kind + "." + extension);
            try (InputStream in = upload.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Stored {} for item {} at {}", kind, itemId, target);
            return target.toString();
        } catch (IOException ex) {
            log.error("Failed to store {} for item {}", kind, itemId, ex);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not save the " + kind);
        }
    }

    private static boolean isPresent(MultipartFile file) {
        return file != null && !file.isEmpty();
    }

    private static String contentTypeOf(MultipartFile upload) {
        String type = upload.getContentType();
        if (type == null) {
            return "";
        }
        int parameters = type.indexOf(';');
        return (parameters < 0 ? type : type.substring(0, parameters))
                .trim().toLowerCase(Locale.ROOT);
    }

    // --- mapping and parsing ----------------------------------------------

    private MovieAdminDto toDto(MediaItem item, boolean created) {
        String base = "/api/media/items/" + item.getId();
        return new MovieAdminDto(
                item.getId(),
                created,
                item.getType().name(),
                item.getLibraryName(),
                item.getTitle(),
                item.getOriginalTitle(),
                item.getSortTitle(),
                item.getYear(),
                item.getPlot(),
                item.getTagline(),
                item.getRuntimeMinutes(),
                item.getRating(),
                item.getCertification(),
                List.copyOf(item.getGenres()),
                split(item.getDirectors()),
                split(item.getCastMembers()),
                item.getStudio(),
                item.getReleaseDate() == null ? null : item.getReleaseDate().toString(),
                item.getTmdbId(),
                item.getImdbId(),
                item.getQuality(),
                item.isCatalogOnly() ? null : item.getFilePath(),
                item.isMissing() || item.isCatalogOnly(),
                item.hasPoster(),
                item.hasBackdrop(),
                item.hasPoster() ? base + "/poster" : null,
                item.hasBackdrop() ? base + "/backdrop" : null,
                item.getMetadataSource() == null ? null : item.getMetadataSource().name(),
                item.playCount(),
                item.getLikeCount(),
                comments.countFor(item.getId()),
                item.getAddedAt() == null ? null : item.getAddedAt().toString(),
                item.getUpdatedAt() == null ? null : item.getUpdatedAt().toString());
    }

    private static MediaType parseType(String raw) {
        try {
            return MediaType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Unknown type '" + raw + "'; expected one of FILM, ANIME, HOME_VIDEO, "
                            + "MUSIC, PHOTO");
        }
    }

    private static LocalDate parseDate(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "releaseDate must be an ISO date such as 1954-04-26");
        }
    }

    /** Order is meaning here: a cast list is in billing order and must keep it. */
    private static String join(List<String> values) {
        String joined = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return joined.isEmpty() ? null : joined;
    }

    private static List<String> split(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return List.of(joined.split("\\s*,\\s*"));
    }

    private static Set<String> cleanSet(List<String> values) {
        Set<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return cleaned;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
