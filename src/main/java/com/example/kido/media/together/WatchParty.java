package com.example.kido.media.together;

import java.time.Instant;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Several people agreeing to watch one title together, on their own devices.
 *
 * <p>The party is persisted but its <em>playhead</em> is not — that lives in
 * {@code WatchPartyRegistry} alongside the open sockets, for the same reason
 * {@code PlaybackSession} is not persisted: a position written every two seconds would
 * turn a film into a stream of database writes, and the value is meaningless after a
 * restart because the sockets holding the party together are gone too. What is worth
 * keeping across a restart is who was invited and who was let in.
 *
 * <p>The title is referenced by id rather than a JPA association, matching
 * {@code MediaItemLike}: a party should not break because a disk was unplugged and the
 * item was marked missing mid-film.
 *
 * <h2>Why the join code is globally unique, not unique-among-live</h2>
 * An ended party keeps its code reserved forever. That wastes codes at a rate of a few
 * per evening against a space of roughly a billion, and buys something worth more: a
 * code forwarded in a chat months ago can never land someone in a <em>different</em>
 * party that happens to have been issued the same six characters.
 */
@Entity
@Table(name = "watch_parties",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_party_join_code", columnNames = "join_code"),
        indexes = {
                @Index(name = "idx_party_host", columnList = "host_account_id, created_at"),
                @Index(name = "idx_party_item", columnList = "media_item_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchParty {

    /** How many people may be admitted at once when the creator does not say. */
    public static final int DEFAULT_MAX_MEMBERS = 4;

    @Id
    @UuidGenerator
    private String id;

    /**
     * What someone types to join, e.g. {@code K7M2QP}.
     *
     * <p>Six characters from an alphabet with {@code 0 O 1 I L} removed, because this
     * gets read aloud and retyped on a TV remote as often as it gets pasted.
     */
    @Column(name = "join_code", nullable = false, length = 6)
    private String joinCode;

    /** The account that created it — the only one that may admit guests or end it. */
    @Column(name = "host_account_id", nullable = false)
    private String hostAccountId;

    /** Which of the host's profiles is watching, for their own resume point. */
    @Column(name = "host_profile_id", nullable = false)
    private String hostProfileId;

    @Column(name = "media_item_id", nullable = false)
    private String mediaItemId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /**
     * When the host ended it, or null while it is still running.
     *
     * <p>This is the single authority for whether a guest token still works. The token
     * carries its own expiry, but a party that ends after ten minutes must revoke a
     * four-hour token immediately, so every guest request re-checks this column rather
     * than trusting {@code exp} alone.
     */
    @Column(name = "ended_at")
    private Instant endedAt;

    /**
     * Admitted members, host included.
     *
     * <p>Capped because sync is the easy half of this feature and bandwidth is the
     * half that decides whether it works: each member is an independent stream off one
     * box, and a title that needs transcoding costs one ffmpeg process each.
     */
    @Column(name = "max_members", nullable = false)
    @Builder.Default
    private int maxMembers = DEFAULT_MAX_MEMBERS;

    /**
     * Whether an anonymous guest needs the host to tap accept.
     *
     * <p>On by default. Holding a code is weak evidence of an invitation — codes get
     * forwarded, screenshotted and shoulder-read — and the host is by definition
     * sitting in front of the screen when it matters.
     */
    @Column(name = "require_approval_for_guests", nullable = false)
    @Builder.Default
    private boolean requireApprovalForGuests = true;

    public boolean isLive() {
        return endedAt == null;
    }
}
