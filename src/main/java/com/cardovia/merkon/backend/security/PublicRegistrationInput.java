package com.cardovia.merkon.backend.security;

import java.text.Normalizer;
import org.springframework.http.HttpStatus;

/** Canonicalizes public input before it reaches persistence or password policy. */
record PublicRegistrationInput(String normalizedEmail, String displayName, String businessName) {

    static PublicRegistrationInput from(PublicRegistrationRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.termsAccepted())) {
            throw invalid();
        }
        return new PublicRegistrationInput(
                normalizeEmail(request.email()),
                normalizeRequired(request.displayName(), 120),
                normalizeRequired(request.businessName(), 160));
    }

    static String canonicalPublicEmailOrNull(String rawEmail) {
        PublicEmailIdentity identity = PublicEmailIdentity.orNull(rawEmail);
        return identity == null ? null : identity.canonicalAscii();
    }

    private static String normalizeEmail(String rawEmail) {
        try {
            return PublicEmailIdentity.require(rawEmail).canonicalAscii();
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static String normalizeRequired(String value, int maximumLength) {
        String normalized = normalize(value);
        if (normalized.isEmpty() || normalized.length() > maximumLength || containsControl(normalized)) {
            throw invalid();
        }
        return normalized;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).strip();
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static SecurityApiException invalid() {
        return new SecurityApiException(
                "REGISTRATION_INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Solicitud de registro invalida.");
    }
}
