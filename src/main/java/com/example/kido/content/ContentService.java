package com.example.kido.content;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.content.dto.ContentDtos.ContentItemDto;
import com.example.kido.content.dto.ContentDtos.ManifestDto;
import com.example.kido.content.dto.ContentDtos.ManifestEntry;
import com.example.kido.content.dto.ContentDtos.PublishRequest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ContentService {

    private final ContentRepository content;

    public ContentService(ContentRepository content) {
        this.content = content;
    }

    /** What has changed since the client's cached version. */
    public ManifestDto manifest(long since) {
        List<ManifestEntry> items = content.findByPublishedTrueAndVersionGreaterThan(since).stream()
                .map(ManifestEntry::from).toList();
        return new ManifestDto(content.maxVersion(), items);
    }

    public List<ContentItemDto> byType(String type) {
        return content.findByTypeAndPublishedTrue(type).stream().map(ContentItemDto::from).toList();
    }

    /** Upsert + publish a content item, bumping the global version. */
    public ContentItemDto publish(PublishRequest req) {
        if (req.type() == null || req.type().isBlank() || req.key() == null || req.key().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "type and key are required");
        }
        long nextVersion = content.maxVersion() + 1;
        ContentItem item = content.findByTypeAndKey(req.type(), req.key()).orElseGet(ContentItem::new);
        item.setType(req.type());
        item.setKey(req.key());
        item.setBody(req.body());
        item.setVersion(nextVersion);
        item.setPublished(true);
        item.setUpdatedAt(Instant.now());
        ContentItemDto dto = ContentItemDto.from(content.save(item));
        log.info("Published content type='{}' key='{}' version={}", req.type(), req.key(), nextVersion);
        return dto;
    }
}
