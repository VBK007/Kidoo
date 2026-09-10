package com.example.kido.media.home;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.dto.HomeDtos.HomeDto;
import com.example.kido.media.dto.HomeDtos.HomeRailDto;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;

/** The home screen, composed server-side. Profile-scoped like everything else. */
@RestController
@RequestMapping("/api/media")
public class HomeController {

    private final HomeService service;

    public HomeController(HomeService service) {
        this.service = service;
    }

    /**
     * Everything the home screen draws: the continue-watching row, the ranked rails and
     * the library header, in one call.
     *
     * @param types comma-separated {@link MediaType} names; defaults to films and anime
     * @param limit posters per rail, capped at 50
     */
    @GetMapping("/home")
    public HomeDto home(@ActiveProfile Profile profile,
                        @RequestParam(required = false) String types,
                        @RequestParam(defaultValue = "20") int limit) {
        return service.home(profile, parseTypes(types), limit);
    }

    /**
     * Just the blended rail — rating, plays and likes in one order — for a client that
     * refreshes it on its own, or for checking why a title placed where it did.
     */
    @GetMapping("/home/popular")
    public HomeRailDto popular(@ActiveProfile Profile profile,
                               @RequestParam(required = false) String types,
                               @RequestParam(defaultValue = "20") int limit) {
        return service.popularRail(profile, parseTypes(types), limit);
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
