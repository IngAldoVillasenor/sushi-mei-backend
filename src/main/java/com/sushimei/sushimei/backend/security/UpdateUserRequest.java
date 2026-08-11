package com.sushimei.sushimei.backend.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @NotBlank @Size(max = 120) String displayName,
        @NotNull ApplicationRole role,
        @NotNull Boolean active,
        @NotNull @Min(0) Long version) {
}