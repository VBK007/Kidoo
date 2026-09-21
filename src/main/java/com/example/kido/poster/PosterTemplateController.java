package com.example.kido.poster;

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

import com.example.kido.poster.dto.PosterDtos.TemplateDto;
import com.example.kido.poster.dto.PosterDtos.TemplatePageDto;
import com.example.kido.poster.dto.PosterDtos.TemplateRequest;
import com.example.kido.user.AppUser;

import jakarta.validation.Valid;

/**
 * Ceremony poster templates.
 *
 * <p>Reads are open to any signed-in profile; writes want a parent account and the
 * admin key, because this is the catalog every household sees rather than one
 * person's poster.
 *
 * <p>{@code GET /{category}} takes a category and not an id, as the brief for this
 * module asks. That works because the categories are a closed enum, so the path can
 * never be mistaken for a uuid — {@code GET /by-id/{id}} is the one that fetches a
 * single template.
 */
@RestController
@RequestMapping("/api/poster/templates")
public class PosterTemplateController {

    private final PosterTemplateService service;
    private final PosterAdminAccess admin;

    public PosterTemplateController(PosterTemplateService service, PosterAdminAccess admin) {
        this.service = service;
        this.admin = admin;
    }

    /**
     * Published templates, a page at a time, ordered by category and then by the order
     * a designer gave them.
     *
     * @param view          {@code summary} drops the layout from each item — the right
     *                      call for a picker grid, which draws thumbnails
     * @param includeDrafts admin-only: also returns unpublished rows, so a set can be
     *                      reviewed before anyone's phone lists it
     */
    @GetMapping
    public TemplatePageDto<?> all(@AuthenticationPrincipal AppUser user,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "40") int size,
                                  @RequestParam(required = false) String view,
                                  @RequestParam(defaultValue = "false") boolean includeDrafts,
                                  @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (includeDrafts) {
            admin.require(user, key);
        }
        return service.list(includeDrafts, page, size, summary(view));
    }

    /** One ceremony's templates: {@code marriage}, {@code baby-shower}, … */
    @GetMapping("/{category}")
    public TemplatePageDto<?> byCategory(@AuthenticationPrincipal AppUser user,
                                         @PathVariable String category,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "40") int size,
                                         @RequestParam(required = false) String view,
                                         @RequestParam(defaultValue = "false") boolean includeDrafts,
                                         @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (includeDrafts) {
            admin.require(user, key);
        }
        return service.byCategory(category, includeDrafts, page, size, summary(view));
    }

    /** A single template, for a client that kept an id rather than a category. */
    @GetMapping("/by-id/{id}")
    public TemplateDto one(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateDto create(@AuthenticationPrincipal AppUser user,
                              @RequestHeader(value = "X-Admin-Key", required = false) String key,
                              @Valid @RequestBody TemplateRequest request) {
        admin.require(user, key);
        return service.create(request);
    }

    /** Replaces the template whole — see {@link PosterTemplateService#update}. */
    @PutMapping("/{id}")
    public TemplateDto update(@AuthenticationPrincipal AppUser user,
                              @RequestHeader(value = "X-Admin-Key", required = false) String key,
                              @PathVariable String id,
                              @Valid @RequestBody TemplateRequest request) {
        admin.require(user, key);
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AppUser user,
                       @RequestHeader(value = "X-Admin-Key", required = false) String key,
                       @PathVariable String id) {
        admin.require(user, key);
        service.delete(id);
    }

    /**
     * Anything other than {@code summary} is the full view, including a misspelling of
     * it. A listing is not the place to refuse a request over a query parameter that
     * only decides how much of each row to send.
     */
    private boolean summary(String view) {
        return "summary".equalsIgnoreCase(view);
    }
}
