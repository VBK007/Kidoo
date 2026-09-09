package com.example.kido.progress;

import org.springframework.stereotype.Service;

import com.example.kido.auth.dto.ProgressDto;
import com.example.kido.user.AppUser;
import com.example.kido.user.Progress;
import com.example.kido.user.UserRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ProgressService {

    private final UserRepository userRepository;

    public ProgressService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public ProgressDto get(AppUser user) {
        return ProgressDto.from(user.getProgress());
    }

    /** Overwrite the stored progress with the supplied values. */
    public ProgressDto replace(AppUser user, ProgressDto dto) {
        user.setProgress(new Progress(dto.stars(), dto.chessWins(), dto.memoryBest()));
        return ProgressDto.from(userRepository.save(user).getProgress());
    }

    /**
     * Offline-friendly merge: keep the best of server and client so no reward
     * is lost when a device syncs after playing offline.
     * stars/chessWins take the max; memoryBest takes the smaller non-zero value.
     */
    public ProgressDto merge(AppUser user, ProgressDto dto) {
        Progress server = user.getProgress();
        int stars = Math.max(server.getStars(), dto.stars());
        int chessWins = Math.max(server.getChessWins(), dto.chessWins());
        int memoryBest = bestMoves(server.getMemoryBest(), dto.memoryBest());
        user.setProgress(new Progress(stars, chessWins, memoryBest));
        ProgressDto merged = ProgressDto.from(userRepository.save(user).getProgress());
        log.debug("Progress sync for username='{}': client={} server-was={} -> merged={}",
                user.getUsername(), dto, server.getStars() + "/" + server.getChessWins() + "/" + server.getMemoryBest(), merged);
        return merged;
    }

    private int bestMoves(int a, int b) {
        if (a == 0) return b;
        if (b == 0) return a;
        return Math.min(a, b);
    }
}
