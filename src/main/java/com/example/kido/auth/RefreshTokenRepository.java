package com.example.kido.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Withdraws every token a user still holds — used when one is replayed, which says
     * a copy has escaped and the device holding the real one can no longer be told
     * apart from whoever took it.
     *
     * <p>In its own transaction, because the caller that detects a replay answers by
     * throwing: joining that transaction would roll this straight back and leave every
     * session the thief can reach still working.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update RefreshToken t set t.revokedAt = :now "
            + "where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") String userId, @Param("now") Instant now);

    /**
     * Drops rows that are past their expiry, spent or not. Called for one user as they
     * sign in, which keeps the table bounded without a scheduled sweep: a token can
     * only be reused before it expires, so nothing past that date is worth keeping to
     * detect reuse with.
     */
    @Modifying
    @Transactional
    @Query("delete from RefreshToken t where t.userId = :userId and t.expiresAt < :now")
    int deleteExpiredForUser(@Param("userId") String userId, @Param("now") Instant now);
}
