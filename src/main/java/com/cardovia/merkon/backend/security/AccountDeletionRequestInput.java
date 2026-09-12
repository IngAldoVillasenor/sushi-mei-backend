package com.cardovia.merkon.backend.security;
import jakarta.validation.constraints.NotBlank;
public record AccountDeletionRequestInput(@NotBlank String email) { }
