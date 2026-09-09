package com.example.kido.profile;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A child profile owned by a parent account (AppUser). */
@Entity
@Table(name = "profiles", indexes = @Index(name = "idx_profile_owner", columnList = "ownerId"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Profile {

    @Id
    @UuidGenerator
    private String id;

    /** AppUser.id of the parent account that owns this profile. */
    @Column(nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    private AgeMode ageMode;

    private String avatarTint; // hex like #FFE9C2

    @Embedded
    @Builder.Default
    private ProfileProgress progress = new ProfileProgress();

    // Per-profile settings
    @Builder.Default
    private boolean narration = true;

    @Builder.Default
    private boolean chessHints = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "profile_badges", joinColumns = @JoinColumn(name = "profile_id"))
    @Column(name = "badge")
    @Builder.Default
    private Set<String> badges = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "profile_pals", joinColumns = @JoinColumn(name = "profile_id"))
    @Column(name = "pal")
    @Builder.Default
    private Set<String> pals = new HashSet<>();

    /** world key (e.g. "chess") -> percent complete 0..100. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "profile_worlds", joinColumns = @JoinColumn(name = "profile_id"))
    @MapKeyColumn(name = "world")
    @Column(name = "pct")
    @Builder.Default
    private Map<String, Integer> worlds = new HashMap<>();

    @Builder.Default
    private Instant createdAt = Instant.now();
}
