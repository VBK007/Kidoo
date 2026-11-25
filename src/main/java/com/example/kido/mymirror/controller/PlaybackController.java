package com.example.kido.mymirror.controller;

import java.security.Principal;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import com.example.kido.mymirror.MirrorBulkhead;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.mymirror.HlsRewriter;
import com.example.kido.mymirror.MirrorCookie;
import com.example.kido.mymirror.service.PlaybackCoalescer;
import com.example.kido.mymirror.service.PlaybackService;

@RestController("mirrorPlaybackController")
@RequestMapping("/playback")
public class PlaybackController {
    private final MirrorBulkhead bulkhead;
    private static final MediaType HLS = MediaType.parseMediaType(HlsRewriter.MIME_TYPE);

    private final PlaybackService service;
    private final PlaybackCoalescer coalescer;
    public PlaybackController(PlaybackService service, MirrorBulkhead bulkhead, PlaybackCoalescer coalescer) {
        this.bulkhead = bulkhead;
        this.service = service;
        this.coalescer = coalescer;
    }

    // Playback keep-alive (p.php). Not a stream resolver — /playlist is the stream source.
    @PostMapping("/heartbeat")
    public DeferredResult<ResponseEntity<String>> heartbeat(@MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.heartbeat(cookie)));
    }

    @GetMapping("/alt")
    public DeferredResult<ResponseEntity<String>> alt(@MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.altVideo(cookie)));
    }

    @GetMapping("/streamtape")
    public DeferredResult<ResponseEntity<String>> streamtape(@MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.streamtape(cookie)));
    }

    @GetMapping("/playlist")
    public DeferredResult<ResponseEntity<String>> playlist(@RequestParam String id, @RequestParam(defaultValue = "") String title,
                                           @MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.playlist(id, title, cookie)));
    }

    // Served as an HLS type: the URL has no .m3u8 extension, so the player goes by this header.
    @GetMapping("/hls")
    public DeferredResult<ResponseEntity<String>> hls(@RequestParam String id, @MirrorCookie String cookie,
                                      Principal principal, HttpServletRequest request) {
        boolean explicitCookie = hasText(request.getHeader(MirrorCookie.HEADER))
                || hasText(request.getParameter(MirrorCookie.PARAM));
        String scope = principal != null ? "principal:" + principal.getName() : explicitCookie ? "cookie" : null;
        String cached = service.cachedHlsMaster(id, cookie, scope);
        if (cached != null) {
            var result = new DeferredResult<ResponseEntity<String>>();
            result.setResult(ResponseEntity.ok().contentType(HLS).body(cached));
            return result;
        }
        // An unscoped (anonymous) caller can't be coalesced — it has no session identity to group on, and
        // hlsMaster takes the bypass path for a null scope — so admit it directly as before. A scoped
        // caller coalesces: the first for this session+title+scope admits one worker slot; peers in the
        // cold-start burst await that one resolve instead of each stampeding the admission gate.
        if (scope == null) {
            return bulkhead.submit(() -> ResponseEntity.ok().contentType(HLS).body(service.hlsMaster(id, cookie, scope)));
        }
        String key = service.masterCoalesceKey(id, cookie, scope);
        return coalescer.master(key, bulkhead.timeoutMillis(),
                () -> bulkhead.submitFuture(() -> ResponseEntity.ok().contentType(HLS).body(service.hlsMaster(id, cookie, scope))));
    }

    // Proxies one variant (media) playlist from the segment CDN, adding the mirror Referer the CDN
    // requires. The u/s pair is a token this server signed when it built the master; no session
    // cookie is involved, so none is read here. Segments are left as absolute CDN URLs the player
    // fetches directly.
    @GetMapping("/variant")
    public DeferredResult<ResponseEntity<String>> variant(@RequestParam String u, @RequestParam String s) {
        return bulkhead.submit(() -> ResponseEntity.ok().contentType(HLS).body(service.variant(u, s)));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @GetMapping("/subtitle")
    public DeferredResult<ResponseEntity<String>> subtitle(@RequestParam String url, @MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.subtitle(url, cookie)));
    }
}
