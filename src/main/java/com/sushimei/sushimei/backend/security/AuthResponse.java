package com.sushimei.sushimei.backend.security;

import java.time.Instant;

public record AuthResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant sessionExpiresAt,
        UserResponse user) {
}