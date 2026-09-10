package com.example.kido.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.kido.user.AppUser;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    /** Marks a token as belonging to a watch party guest rather than an account. */
    public static final String TYPE_GUEST = "guest";

    /** Prefix on a guest subject, so the two kinds of token can never be confused. */
    private static final String GUEST_SUBJECT_PREFIX = "guest:";

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(AppUser user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(user.getId())
                .claim("username", user.getUsername())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * Mints the credential a watch party guest carries.
     *
     * <p>Signed with the same key as an account token, which is safe because the two
     * are told apart by claims rather than by signature: {@code typ} decides which
     * branch of {@link JwtAuthFilter} runs, and the subject is prefixed so a guest id
     * can never be mistaken for a user id even if that check were missed.
     *
     * <p>The expiry is a backstop, not the real limit. What actually revokes this is the
     * party ending, which is checked against the database on every request — a party
     * that finishes after ten minutes must not leave a four-hour token working.
     */
    public String generateGuestToken(String memberId,
                                     String partyId,
                                     String mediaItemId,
                                     String displayName,
                                     Instant expiresAt) {
        return Jwts.builder()
                .subject(GUEST_SUBJECT_PREFIX + memberId)
                .claim("typ", TYPE_GUEST)
                .claim("party", partyId)
                .claim("item", mediaItemId)
                .claim("name", displayName)
                .issuedAt(new Date())
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    /**
     * Returns the user id (subject), or null if the token is invalid, expired or a
     * guest token.
     *
     * <p>Guests are excluded here rather than at the call sites: their subject is not a
     * user id, and letting one through would send every caller looking one up.
     */
    public String extractUserId(String token) {
        return parse(token)
                .filter(claims -> !TYPE_GUEST.equals(claims.get("typ", String.class)))
                .map(Claims::getSubject)
                .orElse(null);
    }

    /** The guest a token identifies, or empty for anything that is not a valid one. */
    public Optional<GuestPrincipal> extractGuest(String token) {
        return parse(token)
                .filter(claims -> TYPE_GUEST.equals(claims.get("typ", String.class)))
                .filter(claims -> claims.getSubject() != null
                        && claims.getSubject().startsWith(GUEST_SUBJECT_PREFIX))
                .map(claims -> new GuestPrincipal(
                        claims.getSubject().substring(GUEST_SUBJECT_PREFIX.length()),
                        claims.get("party", String.class),
                        claims.get("item", String.class),
                        claims.get("name", String.class)));
    }

    private Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
