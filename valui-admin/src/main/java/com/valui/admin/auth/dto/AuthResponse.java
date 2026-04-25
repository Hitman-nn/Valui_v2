package com.valui.admin.auth.dto;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    long expiresIn   // seconds until accessToken expiry
) {}
