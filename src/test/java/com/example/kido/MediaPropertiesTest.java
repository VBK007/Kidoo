package com.example.kido;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.kido.media.MediaPaths;
import com.example.kido.media.MediaProperties;
import com.example.kido.media.catalog.MediaType;

/**
 * Covers how the two library-configuration styles resolve.
 *
 * <p>Worth testing directly because the precedence is load-bearing and invisible: a
 * typed {@code libraries} list silently wins over {@code roots}, which is what lets the
 * server profile carry real paths without disturbing the temporary roots the
 * integration tests configure. Getting that backwards would break either the server or
 * the test suite, and neither failure would point here.
 */
class MediaPropertiesTest {

    private static MediaProperties.Library library(String name, String path, MediaType type) {
        MediaProperties.Library entry = new MediaProperties.Library();
        entry.setName(name);
        entry.setPath(path);
        entry.setType(type);
        return entry;
    }

    @Test
    void typedLibrariesTakePrecedenceOverRoots() {
        MediaProperties props = new MediaProperties();
        props.setRoots(List.of("D:/Fallback"));
        props.setLibraries(List.of(library("Films", "E:/Entertainment", MediaType.FILM)));

        List<MediaProperties.Library> resolved = props.effectiveLibraries();
        assertEquals(1, resolved.size());
        assertEquals("E:/Entertainment", resolved.get(0).getPath());
        assertEquals(MediaType.FILM, resolved.get(0).getType());
    }

    @Test
    void rootsBecomeFilmLibrariesWhenNoneAreTyped() {
        MediaProperties props = new MediaProperties();
        props.setRoots(List.of("D:/Movies", "E:/More Movies"));

        List<MediaProperties.Library> resolved = props.effectiveLibraries();
        assertEquals(2, resolved.size());
        assertTrue(resolved.stream().allMatch(entry -> entry.getType() == MediaType.FILM));
        assertEquals("Films", resolved.get(0).getName());
    }

    @Test
    void blankEntriesAreIgnored() {
        MediaProperties props = new MediaProperties();
        props.setRoots(List.of("", "   "));
        assertTrue(props.effectiveLibraries().isEmpty());

        MediaProperties typed = new MediaProperties();
        typed.setLibraries(List.of(library("Empty", "  ", MediaType.ANIME)));
        assertTrue(typed.effectiveLibraries().isEmpty());
    }

    /** An unnamed library is labelled from its type, so the client always has something. */
    @Test
    void libraryNameDefaultsToTypeLabel() {
        MediaProperties props = new MediaProperties();
        props.setLibraries(List.of(library(null, "E:/Anime", MediaType.ANIME)));
        assertEquals("Anime", props.effectiveLibraries().get(0).getName());

        MediaProperties ours = new MediaProperties();
        ours.setLibraries(List.of(library(null, "E:/Clips", MediaType.HOME_VIDEO)));
        assertEquals("Ours", ours.effectiveLibraries().get(0).getName());
    }

    @Test
    void noConfigurationMeansNoLibraries() {
        assertTrue(new MediaProperties().effectiveLibraries().isEmpty());
    }

    /**
     * A configured path that does not exist is dropped with a warning rather than
     * failing startup — which is what lets the server profile name {@code E:/...} while
     * the same build still boots on a laptop with no such drive.
     */
    @Test
    void missingDirectoriesAreDroppedNotFatal() {
        MediaProperties props = new MediaProperties();
        props.setLibraries(List.of(
                library("Films", "E:/Definitely Not Present " + System.nanoTime(),
                        MediaType.FILM)));

        MediaPaths paths = new MediaPaths(props);
        assertTrue(paths.libraryRoots().isEmpty());
        assertTrue(!paths.isConfigured());
    }

    @Test
    void existingDirectoryResolvesWithItsType(@TempDir Path dir) throws IOException {
        Path anime = Files.createDirectories(dir.resolve("Anime"));

        MediaProperties props = new MediaProperties();
        props.setLibraries(List.of(library("Anime", anime.toString(), MediaType.ANIME)));

        MediaPaths paths = new MediaPaths(props);
        assertEquals(1, paths.libraryRoots().size());
        assertEquals(MediaType.ANIME, paths.libraryRoots().get(0).type());
        assertTrue(paths.isConfigured());
    }

    /** The traversal guard, at the level where a stale row would hit it. */
    @Test
    void pathsOutsideEveryLibraryAreRefused(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("library"));
        Path inside = Files.writeString(root.resolve("film.mp4"), "x");
        Path outside = Files.writeString(dir.resolve("secret.txt"), "x");

        MediaProperties props = new MediaProperties();
        props.setLibraries(List.of(library("Films", root.toString(), MediaType.FILM)));
        MediaPaths paths = new MediaPaths(props);

        assertEquals(inside.toRealPath(), paths.requireWithinRoots(inside.toString()));

        try {
            paths.requireWithinRoots(outside.toString());
            throw new AssertionError("a path outside the library should be refused");
        } catch (com.example.kido.common.ApiException ex) {
            assertEquals(403, ex.getStatus().value());
        }
    }
}
