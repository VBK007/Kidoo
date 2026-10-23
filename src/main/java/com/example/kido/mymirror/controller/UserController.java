package com.example.kido.mymirror.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.mymirror.MirrorCookie;
import com.example.kido.mymirror.service.UserService;

@RestController
@RequestMapping("/user")
public class UserController {
    private final UserService service;
    public UserController(UserService service) { this.service = service; }

    @PostMapping("/watchlist")
    public ResponseEntity<String> watchlist(@RequestParam String id, @MirrorCookie String cookie) {
        return ResponseEntity.ok(service.addWatchlist(id, cookie));
    }

    @GetMapping("/recent")
    public ResponseEntity<String> recent(@MirrorCookie String cookie) {
        return ResponseEntity.ok(service.recentPlay(cookie));
    }

    @PostMapping("/recent")
    public ResponseEntity<String> addRecent(@RequestParam String id, @MirrorCookie String cookie) {
        return ResponseEntity.ok(service.addRecentPlay(id, cookie));
    }

    @DeleteMapping("/recent")
    public ResponseEntity<String> deleteRecent(@RequestParam String id, @MirrorCookie String cookie) {
        return ResponseEntity.ok(service.deleteRecentPlay(id, cookie));
    }

    @GetMapping("/settings")
    public ResponseEntity<String> settings(@MirrorCookie String cookie) {
        return ResponseEntity.ok(service.settings(cookie));
    }

    @PostMapping("/settings/hd")
    public ResponseEntity<String> setHd(@RequestParam(defaultValue = "hd") String hd, @MirrorCookie String cookie) {
        return ResponseEntity.ok(service.setHd(hd, cookie));
    }

    @PostMapping("/settings/ott")
    public ResponseEntity<String> setOtt(@RequestParam String ott, @MirrorCookie String cookie) {
        return ResponseEntity.ok(service.setOtt(ott, cookie));
    }

    @PostMapping("/language")
    public ResponseEntity<String> language(@RequestParam String lang, @MirrorCookie String cookie) {
        return ResponseEntity.ok(service.setLanguage(lang, cookie));
    }
}
