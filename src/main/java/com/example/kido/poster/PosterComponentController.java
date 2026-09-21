package com.example.kido.poster;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.poster.dto.PosterDtos.ComponentDto;
import com.example.kido.poster.dto.PosterDtos.ComponentRequest;
import com.example.kido.user.AppUser;

import jakarta.validation.Valid;

/**
 * The fonts, stickers and frames templates are assembled from.
 *
 * <p>The whole catalog comes back in one call and changes rarely, which is what makes
 * it worth caching on the device: a template response then costs nothing but the ids
 * it points at.
 */
@RestController
@RequestMapping("/api/poster/components")
public class PosterComponentController {

    private final PosterComponentService service;
    private final PosterAdminAccess admin;

    public PosterComponentController(PosterComponentService service, PosterAdminAccess admin) {
        this.service = service;
        this.admin = admin;
    }

    /**
     * @param type optional filter: {@code FONT}, {@code STICKER} or {@code FRAME}
     */
    @GetMapping
    public List<ComponentDto> all(@RequestParam(required = false) String type) {
        return type == null || type.isBlank() ? service.list() : service.byType(type);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentDto create(@AuthenticationPrincipal AppUser user,
                               @RequestHeader(value = "X-Admin-Key", required = false) String key,
                               @Valid @RequestBody ComponentRequest request) {
        admin.require(user, key);
        return service.create(request);
    }

    @PutMapping("/{id}")
    public ComponentDto update(@AuthenticationPrincipal AppUser user,
                               @RequestHeader(value = "X-Admin-Key", required = false) String key,
                               @PathVariable String id,
                               @Valid @RequestBody ComponentRequest request) {
        admin.require(user, key);
        return service.update(id, request);
    }

    /** Refused with 409 while a template still uses it. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AppUser user,
                       @RequestHeader(value = "X-Admin-Key", required = false) String key,
                       @PathVariable String id) {
        admin.require(user, key);
        service.delete(id);
    }
}
