package com.sushimei.sushimei.backend.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(
        @NotBlank String refreshToken,
        @NotBlank @Size(max = 120) String deviceId) {
}