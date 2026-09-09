package com.example.kido.media.catalog;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.dto.CatalogDtos.MovieDetailDto;
import com.example.kido.media.dto.CatalogDtos.MoviePageDto;
import com.example.kido.user.AppUser;

/**
 * Browsing endpoints. Everything is behind the app's existing JWT, so each response is
 * already scoped to the caller for resume state.
 */
@RestController
@RequestMapping("/api/media")
public class CatalogController {

    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    /**
     * Paged library listing.
     *
     * @param q     optional title search
     * @param genre optional genre filter
     * @param sort  {@code title} (default), {@code added}, {@code year} or {@code rating}
     */
    @GetMapping("/movies")
    public MoviePageDto browse(@AuthenticationPrincipal AppUser user,
                               @RequestParam(required = false) String q,
                               @RequestParam(required = false) String genre,
                               @RequestParam(defaultValue = "title") String sort,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "40") int size) {
        return service.browse(user, q, genre, sort, page, size);
    }

    @GetMapping("/movies/{id}")
    public MovieDetailDto detail(@AuthenticationPrincipal AppUser user, @PathVariable String id) {
        return service.detail(user, id);
    }

    @GetMapping("/genres")
    public List<String> genres() {
        return service.genres();
    }
}
