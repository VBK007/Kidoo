package com.example.kido.media.dto;

import java.util.List;

import com.example.kido.media.probe.MediaChapter;
import com.example.kido.media.trickplay.TrickplayManifest;

/** Read models for the player screen: chapter ticks and thumbnail scrubbing. */
public final class PlayerDtos {

    private PlayerDtos() {}

    /**
     * @param title null when the container names no chapter; the client numbers it
     */
    public record ChapterDto(int index, double startSeconds, Double endSeconds, String title) {

        public static ChapterDto from(MediaChapter chapter) {
            return new ChapterDto(
                    chapter.getChapterIndex(),
                    chapter.getStartSeconds(),
                    chapter.getEndSeconds(),
                    chapter.getTitle());
        }
    }

    /**
     * Everything needed to crop a scrub preview locally.
     *
     * <p>The client turns a timecode into a sheet and a tile:
     * <pre>
     * frame  = floor(seconds / intervalSeconds)
     * sheet  = frame / framesPerSheet
     * inside = frame % framesPerSheet
     * column = inside % columns
     * row    = inside / columns
     * </pre>
     * so dragging the scrubber needs no request per frame, and the six-frame filmstrip
     * usually comes from a sheet already in memory.
     *
     * @param sheetUrlTemplate path with a {@code {sheet}} placeholder for the zero-padded
     *                         four-digit sheet index
     */
    public record TrickplayDto(
            String state,
            int intervalSeconds,
            int tileWidth,
            int tileHeight,
            int columns,
            int rows,
            int framesPerSheet,
            int frameCount,
            int sheetCount,
            String sheetUrlTemplate,
            String error) {

        public static TrickplayDto from(TrickplayManifest manifest, String mediaItemId) {
            return new TrickplayDto(
                    manifest.getState().name(),
                    manifest.getIntervalSeconds(),
                    manifest.getTileWidth(),
                    manifest.getTileHeight(),
                    manifest.getColumns(),
                    manifest.getRows(),
                    manifest.framesPerSheet(),
                    manifest.getFrameCount(),
                    manifest.getSheetCount(),
                    "/api/media/items/" + mediaItemId + "/trickplay/sheet_{sheet}.jpg",
                    manifest.getError());
        }
    }

    /** An embedded audio track, for the player's audio chip. */
    public record AudioTrackDto(int index, String codec, String language, String title) {}

    /** Per-file, per-profile subtitle timing correction. */
    public record SubtitleOffsetRequest(double offsetSeconds) {}

    public record TrackSelectionRequest(Integer subtitleTrackIndex, Integer audioTrackIndex) {}

    /** What the player restores on open: position plus last-used tracks. */
    public record PlayerStateDto(
            String mediaItemId,
            double positionSeconds,
            Double durationSeconds,
            boolean watched,
            Double subtitleOffsetSeconds,
            Integer subtitleTrackIndex,
            Integer audioTrackIndex,
            List<ChapterDto> chapters,
            TrickplayDto trickplay) {}
}
