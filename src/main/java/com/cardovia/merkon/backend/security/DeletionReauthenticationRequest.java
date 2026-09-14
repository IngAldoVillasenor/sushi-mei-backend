package com.cardovia.merkon.backend.security;
import jakarta.validation.constraints.NotBlank;
public record DeletionReauthenticationRequest(@NotBlank String currentPassword) { }
