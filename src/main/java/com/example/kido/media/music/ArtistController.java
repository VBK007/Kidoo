package com.example.kido.media.music;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.dto.ArtistDtos.ArtistDetailDto;
import com.example.kido.media.dto.ArtistDtos.ArtistPageDto;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;

/**
 * The music tab's "Artists" category: a grid of tiles (name + image), each opening onto
 * that artist's own catalog — not a flat list of {@code artist — song} rows. See
 * {@link com.example.kido.media.dto.ArtistDtos}.
 */
@RestController
@RequestMapping("/api/media/artists")
public class ArtistController {

    private final ArtistService service;

    public ArtistController(ArtistService service) {
        this.service = service;
    }

    /** @param size artists per page, capped at 100 */
    @GetMapping
    public ArtistPageDto artists(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "30") int size) {
        return service.list(page, size);
    }

    /** The artist's page — everything of theirs in the library, newest first. */
    @GetMapping("/{name}")
    public ArtistDetailDto artist(@ActiveProfile Profile profile,
                                  @PathVariable String name,
                                  @RequestParam(defaultValue = "200") int limit) {
        return service.detail(profile, name, limit);
    }
}
