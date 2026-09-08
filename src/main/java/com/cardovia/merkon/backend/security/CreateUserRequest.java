package com.cardovia.merkon.backend.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(max = 120) String displayName,
        @NotNull String password,
        @NotNull ApplicationRole role,
        @Email @Size(max = 254) String email) {

    public CreateUserRequest(String username, String displayName, String password, ApplicationRole role) {
        this(username, displayName, password, role, null);
    }
}
