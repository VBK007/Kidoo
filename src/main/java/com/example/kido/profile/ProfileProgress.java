package com.example.kido.profile;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Scalar per-profile progress (collections live on the Profile entity). */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProfileProgress {
    private int stars;
    private int streak;
    private int chessLessonStep;
    private int puzzlesSolved;
    private int chessWins;
    private int memoryBest; // fewest moves, 0 = not played
}
