package com.example.kido.auth.dto;

import com.example.kido.user.Progress;

public record ProgressDto(
        int stars,
        int chessWins,
        int memoryBest
) {
    public static ProgressDto from(Progress p) {
        return new ProgressDto(p.getStars(), p.getChessWins(), p.getMemoryBest());
    }
}
