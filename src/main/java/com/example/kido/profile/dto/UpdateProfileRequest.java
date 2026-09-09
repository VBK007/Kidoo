package com.example.kido.profile.dto;

import com.example.kido.profile.AgeMode;

import jakarta.validation.constraints.Size;

/** Partial update — null fields are left unchanged. */
public record UpdateProfileRequest(
        @Size(max = 40) String name,
        AgeMode ageMode,
        String avatarTint
) {}
