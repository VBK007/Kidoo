package com.example.kido.mymirror.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.mymirror.HlsRewriter;
import com.example.kido.mymirror.MirrorCookie;
import com.example.kido.mymirror.service.PlaybackService;

@RestController("mirrorPlaybackController")
@RequestMapping("/playback")
public class PlaybackController {
    private static final MediaType HLS = MediaType.parseMediaType(HlsRewriter.MIME_TYPE);

    private final PlaybackService service;
    public PlaybackController(PlaybackService service) { this.service = service; }

    @PostMapping("/resolve")
    public ResponseEntity<String> resolve(@MirrorCookie String cookie) {
        return ResponseEntity.ok(service.resolveStream(cookie));
    }

    @GetMapping("/alt")
    public ResponseEntity<String> alt(@MirrorCookie String cookie) {
        return ResponseEntity.ok(service.altVideo(cookie));
    }

    @GetMapping("/playlist")
    public ResponseEntity<String> playlist(@RequestParam String id, @RequestParam(defaultValue = "") String title,
                                           @MirrorCookie String cookie) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.playlist(id, title, cookie));
    }

    // Served as an HLS type: the URL has no .m3u8 extension, so the player goes by this header.
    @GetMapping("/hls")
    public ResponseEntity<String> hls(@RequestParam String id, @MirrorCookie String cookie) {
        return ResponseEntity.ok().contentType(HLS).body(service.hlsMaster(id, cookie));
    }

    @GetMapping("/subtitle")
    public ResponseEntity<String> subtitle(@RequestParam String url, @MirrorCookie String cookie) {
        return ResponseEntity.ok(service.subtitle(url, cookie));
    }
}
