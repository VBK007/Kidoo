package com.example.kido.mymirror.controller;

import org.springframework.http.ResponseEntity;
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
    private final InfoRepository repo;
    private final DispatcherService dispatcherService;

    public InfoController(InfoRepository repo, DispatcherService dispatcherService) {
        this.repo = repo;
        this.dispatcherService = dispatcherService;
    }

    @GetMapping("/app")
    public ResponseEntity<String> appInfo(@MirrorCookie String cookie) {
        return ResponseEntity.ok(repo.appInfo(dispatcherService.resolveActiveBaseUrl(), cookie));
    }
}
