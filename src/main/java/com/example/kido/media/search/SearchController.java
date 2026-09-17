package com.example.kido.media.search;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.dto.SearchDtos.SearchResultDto;
import com.example.kido.media.dto.SearchDtos.SearchTermDto;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;

/**
 * Search by sentence rather than by chips.
 *
 * <p>Separate from {@code GET /api/media/items?q=}, which is and stays a title substring
 * match. This endpoint reads the whole phrase — "Tamil films under 2 hours rated over 8"
 * — and returns what it understood along with the results.
 */
@RestController
@RequestMapping("/api/media")
public class SearchController {

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    /**
     * @param q    the sentence, as typed
     * @param page zero-based; {@code size} is capped at 100 like the rest of the catalog
     */
    @GetMapping("/search")
    public SearchResultDto search(@ActiveProfile Profile profile,
                                  @RequestParam(defaultValue = "") String q,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "40") int size) {
        return service.search(profile, q, page, size);
    }

    /**
     * The parse without the results, for a search box that shows chips as somebody
     * types rather than after they press return.
     */
    @GetMapping("/search/interpret")
    public List<SearchTermDto> interpret(@RequestParam(defaultValue = "") String q) {
        return service.interpret(q);
    }
}
