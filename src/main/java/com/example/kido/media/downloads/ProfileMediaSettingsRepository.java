package com.example.kido.media.downloads;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileMediaSettingsRepository
        extends JpaRepository<ProfileMediaSettings, String> {

    Optional<ProfileMediaSettings> findByProfileId(String profileId);
}
