package com.cardovia.merkon.backend.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailVerificationRequest(@NotBlank @Size(max = 512) String token) {
}
