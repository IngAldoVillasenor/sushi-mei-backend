package com.sushimei.sushimei.backend.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(max = 120) String displayName,
        @NotNull String password,
        @NotNull ApplicationRole role) {
}