package com.example.kido.media.music;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.dto.MusicHomeDtos.MusicHomeDto;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;

/**
 * The music tab's home screen — what the client renders when the "Music" chip in the
 * library module is opened. Separate from {@link com.example.kido.media.home.HomeController}
 * because the rail composition is fundamentally different; see
 * {@link com.example.kido.media.dto.MusicHomeDtos} for why.
 */
@RestController
@RequestMapping("/api/media")
public class MusicHomeController {

    private final MusicHomeService service;

    public MusicHomeController(MusicHomeService service) {
        this.service = service;
    }

    /** @param limit tiles per rail, capped at 50 */
    @GetMapping("/home/music")
    public MusicHomeDto musicHome(@ActiveProfile Profile profile,
                                  @RequestParam(defaultValue = "20") int limit) {
        return service.home(profile, limit);
    }
}
