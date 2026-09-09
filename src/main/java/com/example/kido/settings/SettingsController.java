package com.example.kido.settings;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.settings.dto.AccountSettingsDto;
import com.example.kido.settings.dto.ProfileSettingsDto;
import com.example.kido.settings.dto.ScreenTimeDto;
import com.example.kido.user.AppUser;

@RestController
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping("/api/profiles/{id}/settings")
    public ProfileSettingsDto getProfileSettings(@AuthenticationPrincipal AppUser user, @PathVariable String id) {
        return service.getProfileSettings(user, id);
    }

    @PutMapping("/api/profiles/{id}/settings")
    public ProfileSettingsDto updateProfileSettings(@AuthenticationPrincipal AppUser user, @PathVariable String id,
                                                    @RequestBody ProfileSettingsDto dto) {
        return service.updateProfileSettings(user, id, dto);
    }

    @GetMapping("/api/profiles/{id}/screen-time")
    public ScreenTimeDto screenTime(@AuthenticationPrincipal AppUser user, @PathVariable String id) {
        return service.screenTime(user, id);
    }

    @GetMapping("/api/account/settings")
    public AccountSettingsDto getAccountSettings(@AuthenticationPrincipal AppUser user) {
        return service.getAccountSettings(user);
    }

    @PutMapping("/api/account/settings")
    public AccountSettingsDto updateAccountSettings(@AuthenticationPrincipal AppUser user,
                                                    @RequestBody AccountSettingsDto dto) {
        return service.updateAccountSettings(user, dto);
    }
}
