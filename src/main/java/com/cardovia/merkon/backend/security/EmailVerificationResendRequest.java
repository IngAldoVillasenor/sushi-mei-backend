package com.cardovia.merkon.backend.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailVerificationResendRequest(@NotBlank @Size(max = 320) String email) {
}
