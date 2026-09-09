package com.example.kido.auth.dto;

import com.example.kido.user.AppUser;

public record UserDto(
        String id,
        String username,
        String email,
        String displayName,
        String role,
        ProgressDto progress
) {
    public static UserDto from(AppUser u) {
        return new UserDto(
                u.getId(),
                u.getUsername(),
                u.getEmail(),
                u.getDisplayName(),
                u.getRole().name(),
                ProgressDto.from(u.getProgress())
        );
    }
}
