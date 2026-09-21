package com.example.kido.media.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.dto.CatalogDtos.PublicItemDetailDto;
import com.example.kido.media.dto.CatalogDtos.PublicItemPageDto;

/**
 * Browsing for a visitor with no account yet.
 *
 * <p>A deliberately separate tree from {@link CatalogController} rather than an
 * optional-profile branch of it: {@code /api/media/items} decorates every tile with
 * this profile's resume position, watched mark and like, so there is no safe way to
 * call it without one. This mirrors the same category/search/sort options against the
 * same query engine, just without a profile to decorate for — see
 * {@link CatalogService#browsePublic}.
 */
@RestController
@RequestMapping("/api/media/public")
public class PublicCatalogController {

    private final CatalogService service;

    public PublicCatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping("/items")
    public PublicItemPageDto browse(@RequestParam(defaultValue = "all") String category,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) String genre,
                                    @RequestParam(defaultValue = "added") String sort,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "40") int size) {
        return service.browsePublic(category, q, genre, sort, page, size);
    }

    @GetMapping("/items/{id}")
    public PublicItemDetailDto detail(@PathVariable String id) {
        return service.detailPublic(id);
    }
}
