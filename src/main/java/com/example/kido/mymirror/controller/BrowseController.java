package com.example.kido.mymirror.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.mymirror.MirrorCookie;
import com.example.kido.mymirror.service.BrowseService;

@RestController
@RequestMapping("/browse")
public class BrowseController {
    private final BrowseService service;
    public BrowseController(BrowseService service) { this.service = service; }

    @GetMapping("/detail")
    public ResponseEntity<String> detail(@RequestParam String id, @MirrorCookie String cookie) {
        return ResponseEntity.ok(service.detail(id, cookie));
    }

    @GetMapping("/search")
    public ResponseEntity<String> search(@RequestParam String query, @MirrorCookie String cookie) {
        return ResponseEntity.ok(service.search(query, cookie));
    }

    @GetMapping("/episodes")
    public ResponseEntity<String> episodes(@RequestParam String id, @RequestParam String series, @MirrorCookie String cookie) {
        return ResponseEntity.ok(service.episodes(id, series, cookie));
    }
}
