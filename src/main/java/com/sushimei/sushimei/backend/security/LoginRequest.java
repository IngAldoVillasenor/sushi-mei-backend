package com.sushimei.sushimei.backend.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 80) String username,
        @NotNull String password,
        @NotBlank @Size(max = 120) String deviceId,
        @Size(max = 160) String deviceName,
        @Size(max = 40) String appVersion) {
}