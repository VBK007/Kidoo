package com.example.kido.mymirror.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import com.example.kido.mymirror.MirrorBulkhead;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.mymirror.MirrorCookie;
import com.example.kido.mymirror.repo.InfoRepository;
import com.example.kido.mymirror.service.DispatcherService;

@RestController
@RequestMapping("/info")
public class InfoController {
    private final MirrorBulkhead bulkhead;
    private final InfoRepository repo;
    private final DispatcherService dispatcherService;

    public InfoController(InfoRepository repo, DispatcherService dispatcherService, MirrorBulkhead bulkhead) {
        this.bulkhead = bulkhead;
        this.repo = repo;
        this.dispatcherService = dispatcherService;
    }

    @GetMapping("/app")
    public DeferredResult<ResponseEntity<String>> appInfo(@MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(repo.appInfo(dispatcherService.resolveActiveBaseUrl(), cookie)));
    }
}
