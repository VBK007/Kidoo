package com.example.kido.auth.dto;

import com.example.kido.user.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 30) String username,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 4, max = 100) String password,
        @NotBlank String displayName,
        Role role // optional; defaults to CHILD
) {}
