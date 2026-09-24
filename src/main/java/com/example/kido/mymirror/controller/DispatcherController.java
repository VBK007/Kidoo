package com.example.kido.mymirror.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.mymirror.service.DispatcherService;

@RestController
@RequestMapping("/dispatcher")
public class DispatcherController {
    private final DispatcherService service;
    public DispatcherController(DispatcherService service) { this.service = service; }

    @GetMapping("/active")
    public ResponseEntity<String> activeUrl() {
        return ResponseEntity.ok(service.resolveActiveBaseUrl());
    }
}
