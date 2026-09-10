package com.cardovia.merkon.backend.security;

/** Deliberately contains no account, business, session, or token identifiers. */
public record PublicRegistrationResponse(String message) {

    static PublicRegistrationResponse accepted() {
        return new PublicRegistrationResponse("Solicitud de registro aceptada.");
    }
}
