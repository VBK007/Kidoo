package com.example.kido.media.together;

import java.time.Instant;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One person's place in one party — including the ones still knocking.
 *
 * <p>Pending guests are rows rather than a transient queue so that a restart, or a host
 * who backgrounds the app mid-decision, does not lose the request; and so a denial can
 * be remembered rather than re-asked every poll.
 *
 * <h2>Accounts and guests in one table</h2>
 * A member has {@code accountId} and {@code profileId}; a guest has neither and carries
 * a {@code displayName} they typed themselves. Splitting these into two tables would
 * duplicate every membership query for no gain — the party does not care which kind of
 * person is in it, only who and in what state. The nullable columns are the price, and
 * {@code uk_party_account} still works because SQL treats NULLs as distinct: one row
 * per account, unlimited guests.
 */
@Entity
@Table(name = "watch_party_members",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_party_account", columnNames = {"party_id", "account_id"}),
        indexes = {
                @Index(name = "idx_member_party", columnList = "party_id, state"),
                @Index(name = "idx_member_poll", columnList = "poll_token")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchPartyMember {

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "party_id", nullable = false)
    private String partyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PartyRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private MemberState state = MemberState.PENDING;

    /** Null for a guest. Non-null rows are unique per party, so rejoining reuses one. */
    @Column(name = "account_id")
    private String accountId;

    /** Null for a guest: with no account there is no profile to attribute watching to. */
    @Column(name = "profile_id")
    private String profileId;

    /**
     * What the member list shows.
     *
     * <p>Taken from the profile for an account, and typed by the person for a guest —
     * which makes it untrusted input the host reads before deciding. Kept short and
     * sanitised on the way in so the accept prompt cannot be dressed up to look like
     * something it is not.
     */
    @Column(name = "display_name", nullable = false, length = 40)
    private String displayName;

    /**
     * The secret a pending guest polls with, alongside their row id.
     *
     * <p>Separate from the id on purpose: the id travels to the host in the accept
     * prompt and is therefore semi-public within the party, while this never leaves the
     * one device that asked. Knowing a request id must not be enough to collect
     * somebody else's token.
     *
     * <p>Kept for the life of the party rather than cleared on first use, so a guest
     * whose app restarts can fetch their token again. The alternative is knocking a
     * second time and making the host approve the same person twice.
     */
    @Column(name = "poll_token", length = 64)
    private String pollToken;

    /**
     * Identifies the guest JWT handed out for this row, so it can be recognised later.
     *
     * <p>Null for members, who arrive with their own account token and need no second
     * credential.
     */
    @Column(name = "guest_token_id", length = 64)
    private String guestTokenId;

    @Column(name = "requested_at", nullable = false)
    @Builder.Default
    private Instant requestedAt = Instant.now();

    /** When the host let them in; null while pending or denied. */
    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    public boolean isGuest() {
        return role == PartyRole.GUEST;
    }

    public boolean isAdmitted() {
        return state == MemberState.ADMITTED;
    }
}
