package com.example.kido.mymirror.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import com.example.kido.mymirror.MirrorBulkhead;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.mymirror.MirrorCookie;
import com.example.kido.mymirror.service.BrowseService;

@RestController
@RequestMapping("/browse")
public class BrowseController {
    private final MirrorBulkhead bulkhead;
    private final BrowseService service;
    public BrowseController(BrowseService service, MirrorBulkhead bulkhead) {
        this.bulkhead = bulkhead;
        this.service = service;
    }

    @GetMapping("/detail")
    public DeferredResult<ResponseEntity<String>> detail(@RequestParam String id, @MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.detail(id, cookie)));
    }

    @GetMapping("/search")
    public DeferredResult<ResponseEntity<String>> search(@RequestParam String query, @MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.search(query, cookie)));
    }

    @GetMapping("/episodes")
    public DeferredResult<ResponseEntity<String>> episodes(@RequestParam String id, @RequestParam String series, @MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.episodes(id, series, cookie)));
    }

    @GetMapping("/trailer")
    public DeferredResult<ResponseEntity<String>> trailer(@RequestParam String id, @MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(service.trailer(id, cookie)));
    }
}
