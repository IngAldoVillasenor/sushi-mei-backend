package com.cardovia.merkon.backend.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-owned verification-token and resend-abuse policy. */
@ConfigurationProperties(prefix = "merkon.email-verification")
public record EmailVerificationProperties(
        Duration tokenTtl,
        int resendRateLimitMaxAttempts,
        Duration resendRateLimitWindow,
        int transportRateLimitMaxAttempts,
        Duration transportRateLimitWindow) {

    public EmailVerificationProperties {
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative() || tokenTtl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("merkon.email-verification.token-ttl must be positive and no more than seven days");
        }
        if (resendRateLimitMaxAttempts < 1 || resendRateLimitMaxAttempts > 100) {
            throw new IllegalArgumentException("merkon.email-verification.resend-rate-limit-max-attempts must be between 1 and 100");
        }
        if (resendRateLimitWindow == null || resendRateLimitWindow.isZero() || resendRateLimitWindow.isNegative()) {
            throw new IllegalArgumentException("merkon.email-verification.resend-rate-limit-window must be positive");
        }
        if (transportRateLimitMaxAttempts < 10 || transportRateLimitMaxAttempts > 100_000) {
            throw new IllegalArgumentException("merkon.email-verification.transport-rate-limit-max-attempts must be between 10 and 100000");
        }
        if (transportRateLimitWindow == null || transportRateLimitWindow.isZero() || transportRateLimitWindow.isNegative()) {
            throw new IllegalArgumentException("merkon.email-verification.transport-rate-limit-window must be positive");
        }
    }
}
