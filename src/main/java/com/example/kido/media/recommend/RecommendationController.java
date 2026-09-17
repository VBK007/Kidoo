package com.example.kido.media.recommend;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.dto.RecommendationDtos.RecommendationsDto;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;

/**
 * What to watch, for the profile asking.
 *
 * <p>Profile-scoped rather than account-scoped, unlike collections: this is the one
 * endpoint whose entire purpose is to differ between two people in the same house.
 */
@RestController
@RequestMapping("/api/media")
public class RecommendationController {

    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    /**
     * @param types comma-separated {@link MediaType} names; defaults to films and anime
     * @param limit how many picks, capped at 50
     */
    @GetMapping("/recommendations")
    public RecommendationsDto recommendations(
            @ActiveProfile Profile profile,
            @RequestParam(required = false) String types,
            @RequestParam(defaultValue = "20") int limit) {
        return service.forProfile(profile, parseTypes(types), limit);
    }

    /** Unparseable names are ignored rather than failing the request, as elsewhere. */
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
