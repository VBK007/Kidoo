package com.example.kido.mymirror.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import com.example.kido.mymirror.MirrorBulkhead;
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
    private final MirrorBulkhead bulkhead;
    private final UserService service;
    public UserController(UserService service, MirrorBulkhead bulkhead) {
        this.bulkhead = bulkhead;
        this.service = service;
    }

    @PostMapping("/watchlist")
    public DeferredResult<ResponseEntity<String>> watchlist(@RequestParam String id, @MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.addWatchlist(id, cookie)));
    }

    @GetMapping("/recent")
    public DeferredResult<ResponseEntity<String>> recent(@MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.recentPlay(cookie)));
    }

    @PostMapping("/recent")
    public DeferredResult<ResponseEntity<String>> addRecent(@RequestParam String id, @MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.addRecentPlay(id, cookie)));
    }

    @DeleteMapping("/recent")
    public DeferredResult<ResponseEntity<String>> deleteRecent(@RequestParam String id, @MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.deleteRecentPlay(id, cookie)));
    }

    @GetMapping("/settings")
    public DeferredResult<ResponseEntity<String>> settings(@MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.settings(cookie)));
    }

    @PostMapping("/settings/hd")
    public DeferredResult<ResponseEntity<String>> setHd(@RequestParam(defaultValue = "hd") String hd, @MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.setHd(hd, cookie)));
    }

    @PostMapping("/settings/ott")
    public DeferredResult<ResponseEntity<String>> setOtt(@RequestParam String ott, @MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.setOtt(ott, cookie)));
    }

    @PostMapping("/language")
    public DeferredResult<ResponseEntity<String>> language(@RequestParam String lang, @MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.setLanguage(lang, cookie)));
    }
}
