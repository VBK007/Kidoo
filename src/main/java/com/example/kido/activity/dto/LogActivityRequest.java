package com.example.kido.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record LogActivityRequest(
        @NotBlank String world,
        @PositiveOrZero int seconds
) {}
