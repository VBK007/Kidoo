package com.example.kido.activity;

import java.time.Instant;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One logged play session for a profile (drives the parent dashboard). */
@Entity
@Table(name = "activity_events", indexes = @Index(name = "idx_activity_profile_at", columnList = "profileId,at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityEvent {

    @Id
    @UuidGenerator
    private String id;

    @Column(nullable = false)
    private String profileId;

    @Column(nullable = false)
    private String world;

    private int seconds;

    @Builder.Default
    private Instant at = Instant.now();
}
