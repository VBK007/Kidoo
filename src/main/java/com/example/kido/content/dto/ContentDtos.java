package com.example.kido.content.dto;

import java.util.List;

import com.example.kido.content.ContentItem;

public final class ContentDtos {
    private ContentDtos() {}

    public record ManifestEntry(String type, String key, long version) {
        public static ManifestEntry from(ContentItem c) {
            return new ManifestEntry(c.getType(), c.getKey(), c.getVersion());
        }
    }

    public record ManifestDto(long latestVersion, List<ManifestEntry> items) {}

    public record ContentItemDto(String type, String key, long version, String body) {
        public static ContentItemDto from(ContentItem c) {
            return new ContentItemDto(c.getType(), c.getKey(), c.getVersion(), c.getBody());
        }
    }

    public record PublishRequest(String type, String key, String body) {}
}
