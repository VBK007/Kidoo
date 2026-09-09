package com.example.kido.media.catalog;

/** Where a movie's title and artwork came from, so a rescan can prefer better data. */
public enum MetadataSource {
    /** Read from a Kodi-style {@code .nfo} sidecar. */
    NFO,
    /** Derived from the filename because no sidecar was present. */
    FILENAME
}
