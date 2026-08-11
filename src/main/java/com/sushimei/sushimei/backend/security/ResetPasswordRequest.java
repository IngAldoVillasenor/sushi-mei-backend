package com.sushimei.sushimei.backend.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ResetPasswordRequest(
        @NotNull String newPassword,
        @NotNull @Min(0) Long version) {
}