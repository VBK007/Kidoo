package com.example.kido.media.catalog;

/**
 * Where an item's title and artwork came from.
 *
 * <p>Also decides whether a rescan may overwrite them. {@link #MANUAL} is the important
 * one: a correction the owner made by hand has to outlast the scanner that got it wrong
 * in the first place, otherwise fixing a title would be undone the next time the file
 * was touched on disk.
 */
public enum MetadataSource {

    /** Read from a Kodi-style {@code .nfo} sidecar. A rescan may refresh it. */
    NFO,

    /** Guessed from the filename because no sidecar was present. A rescan may re-guess. */
    FILENAME,

    /** Set by the owner through the fix-match screen. A rescan must leave it alone. */
    MANUAL;

    /** True when a scan is allowed to replace the descriptive fields. */
    public boolean isScannerOwned() {
        return this != MANUAL;
    }
}
