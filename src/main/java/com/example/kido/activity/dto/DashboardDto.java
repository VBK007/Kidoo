package com.example.kido.activity.dto;

import java.util.List;

public record DashboardDto(
        String range,
        int learningTimeSeconds,
        int streak,
        int stars,
        List<WorldTime> timeByWorld,
        List<String> learnedNotes
) {
    public record WorldTime(String world, int seconds) {}
}
