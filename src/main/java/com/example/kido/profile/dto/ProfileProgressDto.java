package com.example.kido.profile.dto;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.example.kido.profile.Profile;

/**
 * Full per-profile progress used by GET/PUT/sync. All fields are nullable so a
 * partial offline sync payload (omitting unchanged fields) is accepted; the
 * compact constructor fills sensible defaults (0 / empty), which merge cleanly.
 */
public record ProfileProgressDto(
        Integer stars,
        Integer streak,
        Integer chessLessonStep,
        Integer puzzlesSolved,
        Integer chessWins,
        Integer memoryBest,
        Set<String> badges,
        Set<String> pals,
        Map<String, Integer> worlds
) {
    public ProfileProgressDto {
        if (stars == null) stars = 0;
        if (streak == null) streak = 0;
        if (chessLessonStep == null) chessLessonStep = 0;
        if (puzzlesSolved == null) puzzlesSolved = 0;
        if (chessWins == null) chessWins = 0;
        if (memoryBest == null) memoryBest = 0;
        if (badges == null) badges = new HashSet<>();
        if (pals == null) pals = new HashSet<>();
        if (worlds == null) worlds = new HashMap<>();
    }

    public static ProfileProgressDto from(Profile p) {
        return new ProfileProgressDto(
                p.getProgress().getStars(),
                p.getProgress().getStreak(),
                p.getProgress().getChessLessonStep(),
                p.getProgress().getPuzzlesSolved(),
                p.getProgress().getChessWins(),
                p.getProgress().getMemoryBest(),
                new HashSet<>(p.getBadges()),
                new HashSet<>(p.getPals()),
                new HashMap<>(p.getWorlds())
        );
    }
}
