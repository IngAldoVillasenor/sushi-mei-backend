package com.cardovia.merkon.backend.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PasswordRecoveryConfirmRequest(
        @NotBlank @Size(max = 512) String token,
        @NotNull String newPassword) {
}
