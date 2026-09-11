package com.example.kido.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Trades a refresh token for a new access token.
 *
 * <p>Carried in the body rather than the {@code Authorization} header, because the
 * endpoint is reached precisely when the header's token has expired, and because a
 * refresh token is not a bearer credential for anything else on this server.
 */
public record RefreshRequest(
        @NotBlank String refreshToken
) {}
