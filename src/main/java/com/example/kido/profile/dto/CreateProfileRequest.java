package com.example.kido.profile.dto;

import com.example.kido.profile.AgeMode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProfileRequest(
        @NotBlank @Size(max = 40) String name,
        AgeMode ageMode,     // defaults to YOUNG when null
        String avatarTint
) {}
