package com.example.kido.settings.dto;

import com.example.kido.user.AppUser;

/** Account-wide settings. Fields are nullable so PUT can be partial. */
public record AccountSettingsDto(
        String language,
        Integer dailyScreenTimeMin,
        Boolean askBeforePurchases,
        Boolean weeklyEmailSummary
) {
    public static AccountSettingsDto from(AppUser u) {
        var s = u.getAccountSettings();
        return new AccountSettingsDto(s.getLanguage(), s.getDailyScreenTimeMin(),
                s.isAskBeforePurchases(), s.isWeeklyEmailSummary());
    }
}
