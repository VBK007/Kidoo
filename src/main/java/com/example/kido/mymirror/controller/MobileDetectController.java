package com.example.kido.mymirror.controller;

import com.example.kido.mymirror.MobileDetectService;
import com.example.kido.mymirror.model.ClientHeaders;
import com.example.kido.mymirror.model.PageContent;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mobile")
public class MobileDetectController {

    private final MobileDetectService service;

    public MobileDetectController(MobileDetectService service) {
        this.service = service;
    }

    @GetMapping("/home/content")
    public ResponseEntity<PageContent> getPageContent(
            @RequestParam(defaultValue = "1") String app,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        PageContent content = service.extractPageContent(app, new ClientHeaders(userAgent, accept, acceptLanguage));
        return ResponseEntity.ok(content);
    }
}
