package com.example.kido.profile;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<Profile, String> {
    List<Profile> findByOwnerIdOrderByCreatedAtAsc(String ownerId);

    Optional<Profile> findByIdAndOwnerId(String id, String ownerId);

    long countByOwnerId(String ownerId);
}
