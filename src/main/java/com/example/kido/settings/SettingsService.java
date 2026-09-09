package com.example.kido.settings;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.activity.ActivityRepository;
import com.example.kido.common.ApiException;
import com.example.kido.profile.AgeMode;
import com.example.kido.profile.Profile;
import com.example.kido.profile.ProfileRepository;
import com.example.kido.settings.dto.AccountSettingsDto;
import com.example.kido.settings.dto.ProfileSettingsDto;
import com.example.kido.settings.dto.ScreenTimeDto;
import com.example.kido.user.AccountSettings;
import com.example.kido.user.AppUser;
import com.example.kido.user.UserRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SettingsService {

    private final ProfileRepository profiles;
    private final UserRepository users;
    private final ActivityRepository activity;

    public SettingsService(ProfileRepository profiles, UserRepository users, ActivityRepository activity) {
        this.profiles = profiles;
        this.users = users;
        this.activity = activity;
    }

    // --- profile settings --------------------------------------------------

    public ProfileSettingsDto getProfileSettings(AppUser owner, String profileId) {
        return ProfileSettingsDto.from(owned(owner, profileId));
    }

    public ProfileSettingsDto updateProfileSettings(AppUser owner, String profileId, ProfileSettingsDto dto) {
        Profile p = owned(owner, profileId);
        if (dto.readingLevel() != null) p.setAgeMode(parseAge(dto.readingLevel()));
        if (dto.narration() != null) p.setNarration(dto.narration());
        if (dto.chessHints() != null) p.setChessHints(dto.chessHints());
        log.info("Updated profile settings for profile={}", profileId);
        return ProfileSettingsDto.from(profiles.save(p));
    }

    // --- account settings --------------------------------------------------

    public AccountSettingsDto getAccountSettings(AppUser owner) {
        return AccountSettingsDto.from(reload(owner));
    }

    public AccountSettingsDto updateAccountSettings(AppUser owner, AccountSettingsDto dto) {
        AppUser u = reload(owner);
        AccountSettings s = u.getAccountSettings();
        if (dto.language() != null) s.setLanguage(dto.language());
        if (dto.dailyScreenTimeMin() != null) s.setDailyScreenTimeMin(dto.dailyScreenTimeMin());
        if (dto.askBeforePurchases() != null) s.setAskBeforePurchases(dto.askBeforePurchases());
        if (dto.weeklyEmailSummary() != null) s.setWeeklyEmailSummary(dto.weeklyEmailSummary());
        log.info("Updated account settings for account={}", owner.getId());
        return AccountSettingsDto.from(users.save(u));
    }

    // --- screen time -------------------------------------------------------

    public ScreenTimeDto screenTime(AppUser owner, String profileId) {
        owned(owner, profileId);
        int limitMin = reload(owner).getAccountSettings().getDailyScreenTimeMin();
        var startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        int usedSeconds = activity.findByProfileIdAndAtAfter(profileId, startOfToday).stream()
                .mapToInt(e -> e.getSeconds()).sum();
        return new ScreenTimeDto(limitMin, usedSeconds, usedSeconds >= limitMin * 60);
    }

    // --- helpers -----------------------------------------------------------

    private Profile owned(AppUser owner, String profileId) {
        return profiles.findByIdAndOwnerId(profileId, owner.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Profile not found"));
    }

    private AppUser reload(AppUser owner) {
        return users.findById(owner.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account not found"));
    }

    private AgeMode parseAge(String v) {
        try {
            return AgeMode.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "readingLevel must be YOUNG or OLDER");
        }
    }
}
