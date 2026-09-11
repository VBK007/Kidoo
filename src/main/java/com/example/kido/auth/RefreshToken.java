package com.example.kido.auth;

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
 * One long-lived credential a device may trade for a fresh access token.
 *
 * <p>Stored, unlike the access token, because the point of it is to be revocable: a JWT
 * is believed on its signature alone and nothing can take it back before it expires,
 * which is tolerable for something that lives minutes and not for something that keeps
 * a phone signed in for weeks.
 *
 * <p>Only a SHA-256 of the token is kept. The value itself is shown once, in the
 * response that mints it, and never again — a dump of this table hands out no sessions.
 * A plain hash rather than BCrypt is deliberate and safe here: this is 256 bits of
 * {@link java.security.SecureRandom} output, not a password, so there is nothing to
 * guess and the lookup has to be an indexed equality match rather than a scan.
 *
 * <p>Rows outlive their usefulness on purpose. A token that has been spent is kept,
 * marked revoked, until it would have expired anyway, so that presenting it again is
 * recognised as reuse rather than mistaken for a token that never existed.
 */
@Entity
@Table(name = "refresh_tokens",
        indexes = {
                @Index(name = "uk_refresh_token_hash", columnList = "token_hash", unique = true),
                @Index(name = "idx_refresh_token_user", columnList = "user_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    @Id
    @UuidGenerator
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    /** Base64url of SHA-256 over the token that was handed to the client. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    @Builder.Default
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set when the token is spent or withdrawn; null while it is still usable. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt.isBefore(now);
    }
}
