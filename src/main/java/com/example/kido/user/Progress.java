package com.example.kido.user;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Reward/progress data embedded in the user table. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Progress {
    private int stars;
    private int chessWins;
    private int memoryBest; // fewest moves, 0 = not played
}
