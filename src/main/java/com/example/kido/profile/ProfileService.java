package com.example.kido.profile;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.common.ApiException;
import com.example.kido.profile.dto.CreateProfileRequest;
import com.example.kido.profile.dto.ProfileDto;
import com.example.kido.profile.dto.ProfileProgressDto;
import com.example.kido.profile.dto.UpdateProfileRequest;
import com.example.kido.user.AppUser;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ProfileService {

    private static final int MAX_PROFILES = 6;

    private final ProfileRepository profiles;

    public ProfileService(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    // --- CRUD --------------------------------------------------------------

    public List<ProfileDto> list(AppUser owner) {
        return profiles.findByOwnerIdOrderByCreatedAtAsc(owner.getId()).stream()
                .map(ProfileDto::from).toList();
    }

    public ProfileDto create(AppUser owner, CreateProfileRequest req) {
        if (profiles.countByOwnerId(owner.getId()) >= MAX_PROFILES) {
            throw new ApiException(HttpStatus.CONFLICT, "You can add up to " + MAX_PROFILES + " children");
        }
        Profile p = Profile.builder()
                .ownerId(owner.getId())
                .name(req.name().trim())
                .ageMode(req.ageMode() != null ? req.ageMode() : AgeMode.YOUNG)
                .avatarTint(req.avatarTint())
                .build();
        Profile saved = profiles.save(p);
        log.info("Created profile id={} name='{}' for owner={}", saved.getId(), saved.getName(), owner.getId());
        return ProfileDto.from(saved);
    }

    public ProfileDto get(AppUser owner, String id) {
        return ProfileDto.from(owned(owner, id));
    }

    public ProfileDto update(AppUser owner, String id, UpdateProfileRequest req) {
        Profile p = owned(owner, id);
        if (req.name() != null && !req.name().isBlank()) p.setName(req.name().trim());
        if (req.ageMode() != null) p.setAgeMode(req.ageMode());
        if (req.avatarTint() != null) p.setAvatarTint(req.avatarTint());
        return ProfileDto.from(profiles.save(p));
    }

    public void delete(AppUser owner, String id) {
        Profile p = owned(owner, id);
        profiles.delete(p);
        log.info("Deleted profile id={} for owner={}", id, owner.getId());
    }

    // --- Progress ----------------------------------------------------------

    public ProfileProgressDto getProgress(AppUser owner, String id) {
        return ProfileProgressDto.from(owned(owner, id));
    }

    /** Overwrite the stored progress with the supplied values. */
    public ProfileProgressDto replaceProgress(AppUser owner, String id, ProfileProgressDto dto) {
        Profile p = owned(owner, id);
        ProfileProgress pr = p.getProgress();
        pr.setStars(dto.stars());
        pr.setStreak(dto.streak());
        pr.setChessLessonStep(dto.chessLessonStep());
        pr.setPuzzlesSolved(dto.puzzlesSolved());
        pr.setChessWins(dto.chessWins());
        pr.setMemoryBest(dto.memoryBest());
        p.setBadges(dto.badges() != null ? new HashSet<>(dto.badges()) : new HashSet<>());
        p.setPals(dto.pals() != null ? new HashSet<>(dto.pals()) : new HashSet<>());
        p.setWorlds(dto.worlds() != null ? new HashMap<>(dto.worlds()) : new HashMap<>());
        return ProfileProgressDto.from(profiles.save(p));
    }

    /**
     * Offline-friendly merge: keep the best of server and client so nothing a
     * child earned offline is lost. Counts take the max; memoryBest the smaller
     * non-zero; badges/pals union; each world's percent the max.
     */
    public ProfileProgressDto mergeProgress(AppUser owner, String id, ProfileProgressDto dto) {
        Profile p = owned(owner, id);
        ProfileProgress pr = p.getProgress();
        pr.setStars(Math.max(pr.getStars(), dto.stars()));
        pr.setStreak(Math.max(pr.getStreak(), dto.streak()));
        pr.setChessLessonStep(Math.max(pr.getChessLessonStep(), dto.chessLessonStep()));
        pr.setPuzzlesSolved(Math.max(pr.getPuzzlesSolved(), dto.puzzlesSolved()));
        pr.setChessWins(Math.max(pr.getChessWins(), dto.chessWins()));
        pr.setMemoryBest(bestMoves(pr.getMemoryBest(), dto.memoryBest()));
        if (dto.badges() != null) p.getBadges().addAll(dto.badges());
        if (dto.pals() != null) p.getPals().addAll(dto.pals());
        if (dto.worlds() != null) mergeWorlds(p.getWorlds(), dto.worlds());
        ProfileProgressDto merged = ProfileProgressDto.from(profiles.save(p));
        log.debug("Merged progress for profile={} -> stars={} streak={}", id, merged.stars(), merged.streak());
        return merged;
    }

    // --- helpers -----------------------------------------------------------

    private Profile owned(AppUser owner, String id) {
        return profiles.findByIdAndOwnerId(id, owner.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Profile not found"));
    }

    private int bestMoves(int a, int b) {
        if (a == 0) return b;
        if (b == 0) return a;
        return Math.min(a, b);
    }

    private void mergeWorlds(Map<String, Integer> server, Map<String, Integer> client) {
        for (Map.Entry<String, Integer> e : client.entrySet()) {
            server.merge(e.getKey(), e.getValue(), Math::max);
        }
    }
}
