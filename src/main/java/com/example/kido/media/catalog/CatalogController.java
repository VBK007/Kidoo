package com.example.kido.media.catalog;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.dto.CatalogDtos.ItemDetailDto;
import com.example.kido.media.dto.CatalogDtos.ItemPageDto;
import com.example.kido.media.dto.CatalogDtos.ItemSummaryDto;
import com.example.kido.media.dto.CatalogDtos.LibrarySummaryDto;
import com.example.kido.media.dto.CatalogDtos.TimelineDto;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;

/**
 * Browsing endpoints.
 *
 * <p>Everything is behind the app's existing JWT, and every response is scoped to the
 * profile named by the {@code X-Profile-Id} header, so resume state and unwatched marks
 * belong to the person holding the phone rather than to the household account.
 */
@RestController
@RequestMapping("/api/media")
public class CatalogController {

    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    /**
     * Paged library listing, backing the grid and the search screen.
     *
     * @param category chip value: {@code all} (default), {@code FILM}, {@code ANIME},
     *                 {@code HOME_VIDEO} (or {@code ours}), {@code MUSIC}, {@code PHOTO}
     * @param q        optional title or filename search
     * @param unwatched restrict to unfinished items, for the {@code UNWATCHED} chip
     * @param minHeight minimum vertical resolution, for the {@code 4K ONLY} chip (2160)
     * @param sort     {@code title} (default), {@code added}, {@code captured},
     *                 {@code year} or {@code rating}
     */
    @GetMapping("/items")
    public ItemPageDto browse(@ActiveProfile Profile profile,
                              @RequestParam(defaultValue = "all") String category,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) String genre,
                              @RequestParam(defaultValue = "false") boolean unwatched,
                              @RequestParam(required = false) Integer minHeight,
                              @RequestParam(defaultValue = "title") String sort,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "40") int size) {
        return service.browse(profile, category, q, genre, sort, unwatched, minHeight, page, size);
    }

    @GetMapping("/items/{id}")
    public ItemDetailDto detail(@ActiveProfile Profile profile, @PathVariable String id) {
        return service.detail(profile, id);
    }

    /**
     * Recently-added rail on the home screen.
     *
     * @param types comma-separated {@link MediaType} names; defaults to films and anime
     */
    @GetMapping("/recently-added")
    public List<ItemSummaryDto> recentlyAdded(@ActiveProfile Profile profile,
                                              @RequestParam(required = false) String types,
                                              @RequestParam(defaultValue = "20") int limit) {
        return service.recentlyAdded(profile, parseTypes(types), limit);
    }

    /** Counts, total size, per-category breakdown and the genre facet list. */
    @GetMapping("/library-summary")
    public LibrarySummaryDto summary() {
        return service.summary();
    }

    /**
     * The home-video timeline.
     *
     * @param groupBy {@code date} (default), {@code person} or {@code place}
     */
    @GetMapping("/timeline")
    public TimelineDto timeline(@ActiveProfile Profile profile,
                               @RequestParam(defaultValue = "date") String groupBy,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "60") int size) {
        return service.timeline(profile, groupBy, page, size);
    }

    @GetMapping("/genres")
    public List<String> genres() {
        return service.genres();
    }

    /** Tagged people, backing the timeline's "By person" chips. */
    @GetMapping("/people")
    public List<String> people() {
        return service.people();
    }

    /** Unparseable names are ignored rather than failing the request. */
    private static List<MediaType> parseTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<MediaType> types = new ArrayList<>();
        for (String part : raw.split(",")) {
            MediaType.parse(part).ifPresent(types::add);
        }
        return types;
    }
}
