package com.example.kido.media.together;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchPartyMemberRepository extends JpaRepository<WatchPartyMember, String> {

    List<WatchPartyMember> findByPartyIdAndStateOrderByRequestedAtAsc(String partyId,
                                                                     MemberState state);

    List<WatchPartyMember> findByPartyIdOrderByRequestedAtAsc(String partyId);

    /** Counts against {@code maxMembers}; only admitted people occupy a seat. */
    long countByPartyIdAndState(String partyId, MemberState state);

    /**
     * The row a returning account reuses.
     *
     * <p>Rejoining after a dropped connection must not create a second row — the
     * unique constraint forbids it, and a member list showing the same person twice
     * would be the visible symptom.
     */
    Optional<WatchPartyMember> findByPartyIdAndAccountId(String partyId, String accountId);

    /**
     * Resolves a pending guest's poll.
     *
     * <p>Both halves are required: the request id is semi-public inside the party
     * because it reaches the host in the accept prompt, so the secret is what actually
     * proves this is the device that asked.
     */
    Optional<WatchPartyMember> findByIdAndPollToken(String id, String pollToken);

    /** Requests nobody answered, for the sweeper to deny rather than leave hanging. */
    List<WatchPartyMember> findByStateAndRequestedAtBefore(MemberState state, Instant before);
}
