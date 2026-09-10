package com.cardovia.merkon.backend.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-owned public-registration policy. None of these values are secrets. */
@ConfigurationProperties(prefix = "merkon.registration")
public record PublicRegistrationProperties(
        String termsVersion,
        int rateLimitMaxAttempts,
        Duration rateLimitWindow,
        int transportRateLimitMaxAttempts) {

    public PublicRegistrationProperties {
        if (termsVersion == null || termsVersion.isBlank() || termsVersion.length() > 64) {
            throw new IllegalArgumentException("merkon.registration.terms-version must be between 1 and 64 characters");
        }
        if (rateLimitMaxAttempts < 1 || rateLimitMaxAttempts > 100) {
            throw new IllegalArgumentException("merkon.registration.rate-limit-max-attempts must be between 1 and 100");
        }
        if (rateLimitWindow == null || rateLimitWindow.isZero() || rateLimitWindow.isNegative()) {
            throw new IllegalArgumentException("merkon.registration.rate-limit-window must be positive");
        }
        if (transportRateLimitMaxAttempts < rateLimitMaxAttempts || transportRateLimitMaxAttempts > 10_000) {
            throw new IllegalArgumentException("merkon.registration.transport-rate-limit-max-attempts must be between the identity limit and 10000");
        }
    }
}
