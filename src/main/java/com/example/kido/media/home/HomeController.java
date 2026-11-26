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
import com.example.kido.mymirror.catalogue.MirrorCatalogueProperties;
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
    private final MirrorCatalogueProperties catalogueProps;

    public HomeController(HomeService service, MirrorHomeService mirrorHome, MirrorBulkhead bulkhead,
                          MirrorCatalogueProperties catalogueProps) {
        this.service = service;
        this.mirrorHome = mirrorHome;
        this.bulkhead = bulkhead;
        this.catalogueProps = catalogueProps;
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

        // Cache-first. When enabled, a single cache-only warm read (L1 in-memory, then the L2
        // last-known-good snapshot) drives both branches below, so the warm catalogue is read at most
        // once per request — and not at all when the flag is off, keeping the historical path a
        // byte-for-byte no-op.
        ResponseEntity<HomeDto> saturated;
        // True only when the saturation fallback carries a usable (non-empty) stale/partial catalogue.
        // In that case we also preserve it if an admitted live refresh hangs past the servlet deadline
        // (see below). For a cold cache or with the flag off there is nothing cached to preserve, so the
        // historical deadline behaviour is left exactly as it was.
        boolean preserveCachedOnDeadline = false;
        if (catalogueProps.getCacheFirst().isEnabled()) {
            var warm = mirrorHome.warmRails(mirrorCookie, ott);
            HomeDto warmBody = warm.rails().isEmpty() ? home : home.withMirror(warm.rails());
            // A usable FRESH catalogue (fresh L1 or an un-expired L2 snapshot, both reported as
            // "cached"): serve it immediately and submit NO upstream enrichment. This is the genuine
            // cache-first guarantee — zero upstream catalogue requests on a fresh hit, and the cached
            // Home is available independently of whether the mirror executor has a free slot, because
            // it never touches the bulkhead at all.
            if ("cached".equals(warm.state()) && !warm.rails().isEmpty()) {
                return completed(ResponseEntity.ok()
                        .header("X-Mirror-Catalogue-State", "cached")
                        .body(warmBody));
            }
            // Stale, partial or cold: refresh through the bounded bulkhead below, with this same warm
            // catalogue as the saturation fallback — so a permitted stale/partial snapshot is served
            // (and reported honestly) when the executor is full, and refresh happens only through the
            // approved bounded mechanism, never an unbounded fan-out.
            saturated = ResponseEntity.ok().header("X-Mirror-Catalogue-State", warm.state()).body(warmBody);
            // A non-empty warm body here is a usable stale/partial catalogue (fresh already returned
            // above): mark it to be preserved on a hung-refresh deadline, not turned into a 504.
            preserveCachedOnDeadline = !warm.rails().isEmpty();
        } else {
            // Flag off: the historical saturation fallback — an empty mirror flagged unavailable.
            saturated = ResponseEntity.ok().header("X-Mirror-Catalogue-State", "unavailable").body(home);
        }

        DeferredResult<ResponseEntity<HomeDto>> result = bulkhead.submitOptional(() -> {
            var mirror = mirrorHome.railWithStatus(mirrorCookie, ott);
            HomeDto payload = mirror.rails().isEmpty() ? home : home.withMirror(mirror.rails());
            return ResponseEntity.ok().header("X-Mirror-Catalogue-State", mirror.state()).body(payload);
        }, Duration.ofSeconds(8), saturated);

        if (preserveCachedOnDeadline) {
            // Catalogue-specific deadline handling for THIS request's DeferredResult only. The bulkhead
            // completes its saturation fallback when the executor is full or the admitted action throws,
            // but an admitted refresh that simply HANGS past the servlet deadline is otherwise answered
            // by the bulkhead's default onTimeout with a DeadlineExceeded error (a 504) — which would
            // discard a perfectly usable cached catalogue. We replace that single timeout handler so the
            // already-computed stale/partial catalogue (with its honest X-Mirror-Catalogue-State header)
            // is served instead. This touches neither the shared MirrorBulkhead nor the foreground
            // playback lane; the executor slot is released by the bulkhead's own future-completion
            // cleanup, and the bulkhead's isSetOrExpired() guard drops the hung refresh if it ever lands,
            // so a late result can never overwrite the served catalogue or a newer snapshot.
            final ResponseEntity<HomeDto> preserved = saturated;
            result.onTimeout(() -> result.setResult(preserved));
        }
        return result;
    }

    /** A DeferredResult already resolved with {@code body}: returned to MVC and completed at once. */
    private static DeferredResult<ResponseEntity<HomeDto>> completed(ResponseEntity<HomeDto> body) {
        var result = new DeferredResult<ResponseEntity<HomeDto>>();
        result.setResult(body);
        return result;
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
