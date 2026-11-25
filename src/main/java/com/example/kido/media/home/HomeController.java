package com.example.kido.media.home;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.catalog.MediaType;
import com.example.kido.media.dto.HomeDtos.HomeDto;
import com.example.kido.media.dto.HomeDtos.HomeRailDto;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.mymirror.MirrorCookie;
import com.example.kido.mymirror.MirrorBulkhead;
import com.example.kido.mymirror.MirrorOtt;
import com.example.kido.mymirror.service.MirrorHomeService;
import com.example.kido.profile.Profile;
import com.example.kido.user.AppUser;

/** The home screen, composed server-side. Profile-scoped like everything else. */
@RestController
@RequestMapping("/api/media")
public class HomeController {

    private final HomeService service;
    private final MirrorHomeService mirrorHome;
    private final MirrorBulkhead bulkhead;

    public HomeController(HomeService service, MirrorHomeService mirrorHome, MirrorBulkhead bulkhead) {
        this.service = service;
        this.mirrorHome = mirrorHome;
        this.bulkhead = bulkhead;
    }

    /**
     * Everything the home screen draws: the continue-watching row, the ranked rails and
     * the library header, in one call.
     *
     * @param types comma-separated {@link MediaType} names; defaults to films and anime
     * @param limit posters per rail, capped at 50
     * @param mirrorCookie the caller's own mirror session when it carries one; otherwise the
     *                     server composes {@code mirror} from its global session. Filled for any
     *                     signed-in account. Note the mirror catalogue carries no age filtering,
     *                     so a child account sees it unfiltered — a deliberate choice for this
     *                     build, not an oversight.
     */
    @GetMapping("/home")
    public DeferredResult<ResponseEntity<HomeDto>> home(@AuthenticationPrincipal AppUser user,
                        @ActiveProfile Profile profile,
                        @RequestParam(required = false) String types,
                        @RequestParam(defaultValue = "20") int limit,
                        @RequestHeader(value = MirrorCookie.HEADER, required = false) String mirrorCookie,
                        @RequestHeader(value = MirrorOtt.HEADER, required = false) String ott) {
        HomeDto home = service.home(user, profile, parseTypes(types), limit);
        return bulkhead.submitOptional(() -> {
            var mirror = mirrorHome.railWithStatus(mirrorCookie, ott);
            HomeDto payload = mirror.rails().isEmpty() ? home : home.withMirror(mirror.rails());
            return ResponseEntity.ok().header("X-Mirror-Catalogue-State", mirror.state()).body(payload);
        }, Duration.ofSeconds(8), ResponseEntity.ok().header("X-Mirror-Catalogue-State", "unavailable").body(home));
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
