package com.example.kido.settings.dto;

import com.example.kido.profile.Profile;

/** Per-profile settings. Fields are nullable so PUT can be partial. */
public record ProfileSettingsDto(
        String readingLevel,   // YOUNG | OLDER
        Boolean narration,
        Boolean chessHints
) {
    public static ProfileSettingsDto from(Profile p) {
        return new ProfileSettingsDto(
                p.getAgeMode() == null ? null : p.getAgeMode().name(),
                p.isNarration(),
                p.isChessHints());
    }
}
