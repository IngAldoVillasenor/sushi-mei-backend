package com.cardovia.merkon.backend.security;

/** Minimal account lifecycle foundation for future self-service registration. */
public enum AccountRegistrationState {
    LEGACY,
    PENDING_EMAIL_VERIFICATION,
    ACTIVE
}
