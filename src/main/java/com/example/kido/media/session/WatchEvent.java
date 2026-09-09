package com.example.kido.media.session;

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

/**
 * An append-only record of seconds actually watched.
 *
 * <p>Exists because {@code PlaybackProgress} cannot answer "how many hours did this
 * household watch on Tuesday". It stores only the latest position, so summing it by
 * day would credit a two-hour film entirely to whichever day it was last touched, and
 * would lose the time completely once someone rewatches from the start.
 *
 * <p>Each row is the increment between two consecutive progress reports, so summing
 * any date range gives real watch time. Increments are bounded against wall-clock
 * elapsed time when they are created, so a forward seek cannot inflate the totals.
 */
@Entity
@Table(name = "media_watch_events",
        indexes = {
                @Index(name = "idx_watch_event_occurred", columnList = "occurred_at"),
                @Index(name = "idx_watch_event_profile", columnList = "profile_id, occurred_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchEvent {

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "profile_id", nullable = false)
    private String profileId;

    @Column(name = "media_item_id", nullable = false)
    private String mediaItemId;

    /** Seconds watched in this increment, already bounded when recorded. */
    @Column(name = "seconds_watched", nullable = false)
    private double secondsWatched;

    @Column(name = "occurred_at", nullable = false)
    @Builder.Default
    private Instant occurredAt = Instant.now();
}
