package com.example.kido.account;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.user.AppUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/account/parent-pin")
public class ParentPinController {

    public record PinRequest(@Pattern(regexp = "^\\d{4}$", message = "must be 4 digits") String pin) {}

    private final ParentPinService service;

    public ParentPinController(ParentPinService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Boolean> status(@AuthenticationPrincipal AppUser user) {
        return Map.of("isSet", service.isSet(user));
    }

    @PostMapping
    public ResponseEntity<Void> set(@AuthenticationPrincipal AppUser user, @Valid @RequestBody PinRequest req) {
        service.setPin(user, req.pin());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify")
    public Map<String, Boolean> verify(@AuthenticationPrincipal AppUser user, @Valid @RequestBody PinRequest req) {
        return Map.of("valid", service.verify(user, req.pin()));
    }
}
