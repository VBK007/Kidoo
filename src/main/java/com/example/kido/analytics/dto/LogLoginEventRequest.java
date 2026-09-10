package com.example.kido.analytics.dto;

import jakarta.validation.constraints.NotBlank;

public record LogLoginEventRequest(
        @NotBlank String deviceId,
        String platform,
        String appVersion
) {}
