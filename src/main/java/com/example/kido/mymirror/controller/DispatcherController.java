package com.example.kido.mymirror.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import com.example.kido.mymirror.MirrorBulkhead;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.mymirror.service.DispatcherService;

@RestController
@RequestMapping("/dispatcher")
public class DispatcherController {
    private final MirrorBulkhead bulkhead;
    private final DispatcherService service;
    public DispatcherController(DispatcherService service, MirrorBulkhead bulkhead) {
        this.bulkhead = bulkhead;
        this.service = service;
    }

    @GetMapping("/active")
    public DeferredResult<ResponseEntity<String>> activeUrl() {
        return bulkhead.submit(() -> ResponseEntity.ok(service.resolveActiveBaseUrl()));
    }
}
