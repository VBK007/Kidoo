package com.example.kido.profile.dto;

import com.example.kido.profile.Profile;

public record ProfileDto(
        String id,
        String name,
        String ageMode,
        String avatarTint,
        int stars,
        int streak
) {
    public static ProfileDto from(Profile p) {
        return new ProfileDto(
                p.getId(),
                p.getName(),
                p.getAgeMode() == null ? null : p.getAgeMode().name(),
                p.getAvatarTint(),
                p.getProgress().getStars(),
                p.getProgress().getStreak()
        );
    }
}
