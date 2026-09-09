package com.example.kido.activity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.kido.activity.dto.DashboardDto;
import com.example.kido.activity.dto.LogActivityRequest;
import com.example.kido.common.ApiException;
import com.example.kido.profile.Profile;
import com.example.kido.profile.ProfileRepository;
import com.example.kido.user.AppUser;

@Service
public class ActivityService {

    private final ActivityRepository events;
    private final ProfileRepository profiles;

    public ActivityService(ActivityRepository events, ProfileRepository profiles) {
        this.events = events;
        this.profiles = profiles;
    }

    public void log(AppUser owner, String profileId, LogActivityRequest req) {
        owned(owner, profileId);
        events.save(ActivityEvent.builder()
                .profileId(profileId)
                .world(req.world())
                .seconds(req.seconds())
                .at(Instant.now())
                .build());
    }

    public DashboardDto dashboard(AppUser owner, String profileId, String range) {
        Profile p = owned(owner, profileId);
        Instant since = "today".equalsIgnoreCase(range)
                ? LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()
                : Instant.now().minus(7, ChronoUnit.DAYS);

        List<ActivityEvent> list = events.findByProfileIdAndAtAfter(profileId, since);

        int total = list.stream().mapToInt(ActivityEvent::getSeconds).sum();
        Map<String, Integer> byWorld = new LinkedHashMap<>();
        for (ActivityEvent e : list) {
            byWorld.merge(e.getWorld(), e.getSeconds(), Integer::sum);
        }
        List<DashboardDto.WorldTime> timeByWorld = byWorld.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> en) -> en.getValue()).reversed())
                .map(en -> new DashboardDto.WorldTime(en.getKey(), en.getValue()))
                .toList();

        return new DashboardDto(
                "today".equalsIgnoreCase(range) ? "today" : "week",
                total,
                p.getProgress().getStreak(),
                p.getProgress().getStars(),
                timeByWorld,
                learnedNotes(p));
    }

    private List<String> learnedNotes(Profile p) {
        List<String> notes = new ArrayList<>();
        var pr = p.getProgress();
        if (pr.getPuzzlesSolved() > 0) notes.add("Solved " + pr.getPuzzlesSolved() + " chess puzzles.");
        if (pr.getChessLessonStep() > 0) notes.add("Reached chess lesson step " + pr.getChessLessonStep() + ".");
        p.getWorlds().forEach((w, pct) -> {
            if (pct != null && pct >= 90) notes.add("Almost finished " + w + ".");
        });
        if (notes.isEmpty() && pr.getStars() > 0) notes.add("Earned " + pr.getStars() + " stars.");
        return notes;
    }

    private Profile owned(AppUser owner, String profileId) {
        return profiles.findByIdAndOwnerId(profileId, owner.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Profile not found"));
    }
}
