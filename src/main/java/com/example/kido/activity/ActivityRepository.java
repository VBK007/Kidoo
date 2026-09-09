package com.example.kido.activity;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<ActivityEvent, String> {
    List<ActivityEvent> findByProfileIdAndAtAfter(String profileId, Instant after);
}
