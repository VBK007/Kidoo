package com.example.kido.auth.dto;

import com.example.kido.user.Role;

import jakarta.validation.constraints.NotBlank;

/**
 * Exchanges a Firebase ID token for this server's own JWT.
 *
 * @param idToken the token the app received from Firebase after Google sign-in
 * @param role    only honoured when the account is being created by this call;
 *                an existing user keeps the role it already has
 */
public record FirebaseLoginRequest(
        @NotBlank String idToken,
        Role role
) {}
