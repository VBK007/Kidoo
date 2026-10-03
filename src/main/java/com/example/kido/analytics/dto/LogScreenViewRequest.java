package com.example.kido.analytics.dto;

import jakarta.validation.constraints.NotBlank;

public record LogScreenViewRequest(
        @NotBlank String deviceId,
        @NotBlank String screen,
        String profileId,
        String itemId,
        String platform
) {}
