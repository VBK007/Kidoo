package com.example.kido.activity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.activity.dto.DashboardDto;
import com.example.kido.activity.dto.LogActivityRequest;
import com.example.kido.user.AppUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/profiles/{id}")
public class ActivityController {

    private final ActivityService service;

    public ActivityController(ActivityService service) {
        this.service = service;
    }

    @PostMapping("/activity")
    public ResponseEntity<Void> log(@AuthenticationPrincipal AppUser user, @PathVariable String id,
                                    @Valid @RequestBody LogActivityRequest req) {
        service.log(user, id, req);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @GetMapping("/dashboard")
    public DashboardDto dashboard(@AuthenticationPrincipal AppUser user, @PathVariable String id,
                                  @RequestParam(defaultValue = "week") String range) {
        return service.dashboard(user, id, range);
    }
}
