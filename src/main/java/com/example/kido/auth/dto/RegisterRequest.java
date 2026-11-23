package com.example.kido.auth.dto;

import com.example.kido.user.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param role optional; honoured as {@code PARENT} only when the request also
 *             carries a matching {@code X-Admin-Key} header — see
 *             {@code AuthService#resolveRole}. Anything else, including a
 *             blank or wrong key, creates a {@code CHILD} account regardless
 *             of what was asked for.
 */
public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 30) String username,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 4, max = 100) String password,
        @NotBlank String displayName,
        Role role
) {}
