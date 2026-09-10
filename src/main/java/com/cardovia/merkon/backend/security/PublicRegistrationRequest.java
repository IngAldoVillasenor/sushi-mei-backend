package com.cardovia.merkon.backend.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Anonymous registration input. Authority fields intentionally do not exist
 * here: the server always creates the initial OWNER membership itself.
 */
public record PublicRegistrationRequest(
        @NotBlank @Size(max = 320) String email,
        @NotBlank @Size(max = 120) String displayName,
        @NotNull String password,
        @NotBlank @Size(max = 160) String businessName,
        @NotNull @AssertTrue Boolean termsAccepted) {
}
