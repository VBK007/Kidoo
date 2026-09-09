package com.example.kido.progress;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.auth.dto.ProgressDto;
import com.example.kido.user.AppUser;

@RestController
@RequestMapping("/api/progress")
public class ProgressController {

    private final ProgressService progressService;

    public ProgressController(ProgressService progressService) {
        this.progressService = progressService;
    }

    @GetMapping
    public ResponseEntity<ProgressDto> get(@AuthenticationPrincipal AppUser user) {
        return ResponseEntity.ok(progressService.get(user));
    }

    @PutMapping
    public ResponseEntity<ProgressDto> replace(@AuthenticationPrincipal AppUser user,
                                               @RequestBody ProgressDto dto) {
        return ResponseEntity.ok(progressService.replace(user, dto));
    }

    @PostMapping("/sync")
    public ResponseEntity<ProgressDto> sync(@AuthenticationPrincipal AppUser user,
                                            @RequestBody ProgressDto dto) {
        return ResponseEntity.ok(progressService.merge(user, dto));
    }
}
