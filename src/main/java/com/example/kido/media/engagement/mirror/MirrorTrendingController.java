package com.example.kido.media.engagement.mirror;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.dto.HomeDtos.MirrorRailDto;

/**
 * "Trending on OTTVault" — one rail, composed from our own members' engagement.
 *
 * <p>Its own endpoint rather than a field folded into {@code /api/media/home}: the home
 * mirror array is composed from the external feed and cached per mirror session, while this
 * rail is a read of our engagement tables that is the same for everyone and belongs to no
 * one mirror cookie. The client fetches it alongside the home and places it among the
 * personal rails it already assembles there (Continue Watching, My List), so the server
 * stays the source of what a rail contains and the client owns only where it sits.
 *
 * <p>It sits under {@code /api/media/mirror} with the rest of the mirror engagement surface,
 * so it falls under the same authenticated-by-default rule — any signed-in member may read
 * the trend, since the trend is the audience's, not any one profile's. No profile header is
 * required: the rail is global by design.
 */
@RestController
@RequestMapping("/api/media/mirror")
public class MirrorTrendingController {

    private final MirrorTrendingService service;

    public MirrorTrendingController(MirrorTrendingService service) {
        this.service = service;
    }

    /**
     * The trending rail. Always 200: when there is not yet enough signal to fill it
     * honestly the rail comes back with no tiles, and the client omits an empty rail exactly
     * as it omits an empty one from the home.
     */
    @GetMapping("/trending")
    public MirrorRailDto trending() {
        return service.trending();
    }
}
