package com.example.kido.mymirror.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import com.example.kido.mymirror.MirrorBulkhead;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.mymirror.MirrorCookie;
import com.example.kido.mymirror.repo.AdGateRepository;
import com.example.kido.mymirror.service.DispatcherService;

@RestController
@RequestMapping("/adgate")
public class AdGateController {
    private final MirrorBulkhead bulkhead;
    private final AdGateRepository repo;
    private final DispatcherService dispatcherService;

    public AdGateController(AdGateRepository repo, DispatcherService dispatcherService, MirrorBulkhead bulkhead) {
        this.bulkhead = bulkhead;
        this.repo = repo;
        this.dispatcherService = dispatcherService;
    }

    @GetMapping("/verify")
    public DeferredResult<ResponseEntity<String>> verify(@MirrorCookie String cookie) {
        return bulkhead.submit(() -> ResponseEntity.ok(repo.verifyAds(dispatcherService.resolveActiveBaseUrl(), cookie)));
    }
}
