package com.example.kido.profile;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.profile.dto.CreateProfileRequest;
import com.example.kido.profile.dto.ProfileDto;
import com.example.kido.profile.dto.ProfileProgressDto;
import com.example.kido.profile.dto.UpdateProfileRequest;
import com.example.kido.user.AppUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProfileDto> list(@AuthenticationPrincipal AppUser user) {
        return service.list(user);
    }

    @PostMapping
    public ResponseEntity<ProfileDto> create(@AuthenticationPrincipal AppUser user,
                                             @Valid @RequestBody CreateProfileRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user, req));
    }

    @GetMapping("/{id}")
    public ProfileDto get(@AuthenticationPrincipal AppUser user, @PathVariable String id) {
        return service.get(user, id);
    }

    @PutMapping("/{id}")
    public ProfileDto update(@AuthenticationPrincipal AppUser user, @PathVariable String id,
                             @Valid @RequestBody UpdateProfileRequest req) {
        return service.update(user, id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUser user, @PathVariable String id) {
        service.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    // --- progress ---------------------------------------------------------

    @GetMapping("/{id}/progress")
    public ProfileProgressDto getProgress(@AuthenticationPrincipal AppUser user, @PathVariable String id) {
        return service.getProgress(user, id);
    }

    @PutMapping("/{id}/progress")
    public ProfileProgressDto replaceProgress(@AuthenticationPrincipal AppUser user, @PathVariable String id,
                                              @RequestBody ProfileProgressDto dto) {
        return service.replaceProgress(user, id, dto);
    }

    @PostMapping("/{id}/progress/sync")
    public ProfileProgressDto syncProgress(@AuthenticationPrincipal AppUser user, @PathVariable String id,
                                           @RequestBody ProfileProgressDto dto) {
        return service.mergeProgress(user, id, dto);
    }
}
