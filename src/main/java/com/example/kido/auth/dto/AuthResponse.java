package com.example.kido.auth.dto;

/**
 * What every way of signing in returns.
 *
 * @param token        the access token, sent as {@code Authorization: Bearer} on every
 *                     later call
 * @param refreshToken exchanged at {@code POST /api/auth/refresh} once the access token
 *                     expires; rotated on each use, so a client must store the newest
 *                     one it was given and never the one it sent
 * @param user         the account, so the client need not decode the token to render a
 *                     name
 */
public record AuthResponse(
        String token,
        String refreshToken,
        UserDto user
) {}
