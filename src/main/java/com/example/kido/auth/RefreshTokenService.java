package com.example.kido.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.kido.common.ApiException;

import lombok.extern.slf4j.Slf4j;

/**
 * Mints, rotates and withdraws the refresh tokens that keep a device signed in.
 *
 * <p>Every refresh rotates: the token presented is spent and a new one comes back with
 * the access token. A stolen copy is therefore only useful until the real device next
 * refreshes, and the moment both are in play one of them is replayed — which is what
 * {@link #rotate} watches for, ending every session the account has rather than letting
 * an unknown second holder ride along indefinitely.
 *
 * <p>Failures are all the same 401 with the same wording on purpose. Telling apart
 * "never existed", "already spent" and "expired" would let someone probe the table.
 */
@Slf4j
@Service
public class RefreshTokenService {

    /** 256 bits of randomness: guessing is not a threat model this has to answer. */
    private static final int TOKEN_BYTES = 32;

    /**
     * How long after a rotation a replay is treated as a race rather than a theft.
     *
     * <p>A phone that fires several calls at once answers several 401s at once and can
     * refresh twice with the same token through no fault of its own. Without this
     * window that honest race looks exactly like reuse and signs the household out.
     * Inside it the late request is simply refused — it retries with the token its
     * sibling already received — and no other session is touched.
     */
    private static final Duration ROTATION_GRACE = Duration.ofSeconds(30);

    private static final String FAILURE_MESSAGE = "Refresh token is invalid or expired";

    private final RefreshTokenRepository repository;
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository,
                               @Value("${app.jwt.refresh-expiration-ms}") long ttlMs) {
        this.repository = repository;
        this.ttl = Duration.ofMillis(ttlMs);
    }

    /** What a rotation produced: whose session it is, and the token replacing the old one. */
    public record Rotation(String userId, String refreshToken) {}

    /**
     * Hands a device a fresh refresh token. The returned value is the only copy — what
     * is stored is a hash of it.
     */
    @Transactional
    public String issue(String userId) {
        Instant now = Instant.now();
        repository.deleteExpiredForUser(userId, now);

        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        repository.save(RefreshToken.builder()
                .userId(userId)
                .tokenHash(hash(raw))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build());
        return raw;
    }

    /**
     * Spends a refresh token and issues its replacement.
     *
     * @throws ApiException 401 if the token is unknown, already spent or past its expiry
     */
    @Transactional
    public Rotation rotate(String rawToken) {
        Instant now = Instant.now();
        RefreshToken stored = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> {
                    log.warn("Refresh rejected: no such token");
                    return unauthorized();
                });

        if (stored.isRevoked()) {
            throw replayed(stored, now);
        }
        if (stored.isExpiredAt(now)) {
            log.info("Refresh rejected: token for user id={} expired at {}",
                    stored.getUserId(), stored.getExpiresAt());
            throw unauthorized();
        }

        stored.setRevokedAt(now);
        repository.save(stored);

        log.info("Rotated refresh token for user id={}", stored.getUserId());
        return new Rotation(stored.getUserId(), issue(stored.getUserId()));
    }

    /**
     * Ends every session on the account.
     *
     * <p>Not annotated here on purpose: the repository method runs in a transaction of
     * its own so that it still commits when the caller goes on to throw.
     */
    public int revokeAll(String userId) {
        return repository.revokeAllForUser(userId, Instant.now());
    }

    /**
     * Decides whether a spent token coming back is an honest race or a second holder,
     * and in the latter case takes the whole account's sessions down with it.
     */
    private ApiException replayed(RefreshToken stored, Instant now) {
        if (stored.getRevokedAt().isAfter(now.minus(ROTATION_GRACE))) {
            log.debug("Refresh rejected: token for user id={} was rotated {}s ago — "
                            + "treating as a concurrent refresh, not reuse",
                    stored.getUserId(),
                    Duration.between(stored.getRevokedAt(), now).toSeconds());
            return unauthorized();
        }
        int revoked = revokeAll(stored.getUserId());
        log.warn("Refresh token reuse detected for user id={}; revoked {} live token(s)",
                stored.getUserId(), revoked);
        return unauthorized();
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, FAILURE_MESSAGE);
    }

    private String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is required of every JVM; if it is missing nothing here can work.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
