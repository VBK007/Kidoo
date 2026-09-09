package com.example.kido.content;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.common.ApiException;
import com.example.kido.content.dto.ContentDtos.ContentItemDto;
import com.example.kido.content.dto.ContentDtos.ManifestDto;
import com.example.kido.content.dto.ContentDtos.PublishRequest;

@RestController
public class ContentController {

    private final ContentService service;
    private final String adminKey;

    public ContentController(ContentService service, @Value("${app.admin.api-key}") String adminKey) {
        this.service = service;
        this.adminKey = adminKey;
    }

    /** Public reads so the app can fetch/cache content offline-first. */
    @GetMapping("/api/content/manifest")
    public ManifestDto manifest(@RequestParam(defaultValue = "0") long since) {
        return service.manifest(since);
    }

    @GetMapping("/api/content/{type}")
    public List<ContentItemDto> byType(@PathVariable String type) {
        return service.byType(type);
    }

    /** Publish content — requires the admin API key (X-Admin-Key header). */
    @PostMapping("/api/admin/content")
    public ContentItemDto publish(@RequestBody PublishRequest req,
                                  @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (adminKey == null || adminKey.isBlank() || !adminKey.equals(key)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Admin key required");
        }
        return service.publish(req);
    }
}
