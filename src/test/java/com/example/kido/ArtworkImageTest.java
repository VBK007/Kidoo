package com.example.kido;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.kido.media.MediaFiles;

/**
 * Which images are somebody's cover art rather than photographs.
 *
 * <p>Written after a film turned up twice in search: once as the 584MB MP4 and once
 * as the 9KB JPEG beside it carrying the same name. The name alone could not tell
 * them apart, so the rule now asks the disk, and a rule that asks the disk is one
 * worth pinning down.
 */
class ArtworkImageTest {

    @TempDir
    Path folder;

    @Test
    void imageNamedAfterASiblingVideoIsArtwork() throws IOException {
        Files.createFile(folder.resolve("Soorarai pottru.mp4"));
        Path cover = Files.createFile(folder.resolve("Soorarai pottru.jpg"));

        assertThat(MediaFiles.isArtworkImage(cover)).isTrue();
    }

    @Test
    void imageNamedAfterASiblingTrackIsArtwork() throws IOException {
        Files.createFile(folder.resolve("Ennullea.mp3"));
        Path cover = Files.createFile(folder.resolve("Ennullea.png"));

        assertThat(MediaFiles.isArtworkImage(cover)).isTrue();
    }

    @Test
    void matchesASiblingWhoseExtensionIsUppercase() throws IOException {
        // A camera writes HOLIDAY.MP4, and on ext4 that is a different filename.
        Files.createFile(folder.resolve("Holiday.MP4"));
        Path cover = Files.createFile(folder.resolve("Holiday.jpg"));

        assertThat(MediaFiles.isArtworkImage(cover)).isTrue();
    }

    @Test
    void windowsMediaPlayerThumbnailCacheIsArtwork() {
        assertThat(MediaFiles.isArtworkImage(
                folder.resolve("AlbumArt {F011C0FA-7E53-4299-AB73-81EBDEF0BD07} Small.jpg")))
                .isTrue();
        assertThat(MediaFiles.isArtworkImage(
                folder.resolve("AlbumArt_{B5020207-474E-4720-FF3C-F46B384D8000}_Large.jpg")))
                .isTrue();
    }

    @Test
    void theOldNameOnlyConventionsStillHold() {
        assertThat(MediaFiles.isArtworkImage(folder.resolve("poster.jpg"))).isTrue();
        assertThat(MediaFiles.isArtworkImage(folder.resolve("Inception-fanart.png"))).isTrue();
        assertThat(MediaFiles.isArtworkImage(folder.resolve("Inception.backdrop.jpg"))).isTrue();
    }

    /**
     * The half that matters most. Over-matching here would silently swallow somebody's
     * photographs, which is a worse failure than the duplicate this was written to fix.
     */
    @Test
    void aPhotographWithNothingBesideItIsNotArtwork() throws IOException {
        Path photo = Files.createFile(folder.resolve("Diwali 2024.jpg"));

        assertThat(MediaFiles.isArtworkImage(photo)).isFalse();
    }

    @Test
    void aPhotographIsNotArtworkJustBecauseVideosShareTheFolder() throws IOException {
        Files.createFile(folder.resolve("Soorarai pottru.mp4"));
        Path photo = Files.createFile(folder.resolve("Diwali 2024.jpg"));

        assertThat(MediaFiles.isArtworkImage(photo)).isFalse();
    }

    @Test
    void aSiblingThatIsAnotherImageDoesNotMakeThisOneArtwork() throws IOException {
        Files.createFile(folder.resolve("Sunset.png"));
        Path photo = Files.createFile(folder.resolve("Sunset.jpg"));

        assertThat(MediaFiles.isArtworkImage(photo)).isFalse();
    }

    @Test
    void nonImagesAreNeverArtwork() {
        assertThat(MediaFiles.isArtworkImage(folder.resolve("Soorarai pottru.mp4"))).isFalse();
        assertThat(MediaFiles.isArtworkImage(folder.resolve("poster.mp4"))).isFalse();
    }
}
