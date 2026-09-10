package com.example.kido.media.together;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchPartyRepository extends JpaRepository<WatchParty, String> {

    /**
     * The lookup every join goes through.
     *
     * <p>Filtered to live parties in the query rather than checked afterwards, so an
     * ended party is indistinguishable from a code that never existed. Telling the two
     * apart would let someone with a list of guesses learn which codes are real.
     */
    Optional<WatchParty> findByJoinCodeAndEndedAtIsNull(String joinCode);

    /** Collision check when minting a code; covers ended parties, which keep theirs. */
    boolean existsByJoinCode(String joinCode);

    /**
     * Whether a party is still running, without loading it.
     *
     * <p>On the path of every guest byte served, so it stays a primary-key existence
     * check rather than a fetch of a row nothing reads.
     */
    boolean existsByIdAndEndedAtIsNull(String id);

    /** What the host's own device shows when it reconnects and needs to find its party. */
    List<WatchParty> findByHostAccountIdAndEndedAtIsNullOrderByCreatedAtDesc(String hostAccountId);

    /** Everything still running — the sweeper's input, and the admin panel's. */
    List<WatchParty> findByEndedAtIsNull();
}
