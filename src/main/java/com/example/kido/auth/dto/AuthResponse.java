package com.example.kido.auth.dto;

public record AuthResponse(
        String token,
        UserDto user
) {}
