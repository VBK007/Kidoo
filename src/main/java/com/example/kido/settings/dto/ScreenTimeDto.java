package com.example.kido.settings.dto;

public record ScreenTimeDto(
        int limitMin,
        int usedSecondsToday,
        boolean limitReached
) {}
