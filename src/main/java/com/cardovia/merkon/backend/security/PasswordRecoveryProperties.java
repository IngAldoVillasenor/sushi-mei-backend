package com.cardovia.merkon.backend.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-owned expiry and abuse policy for public password recovery. */
@ConfigurationProperties(prefix = "merkon.password-recovery")
public record PasswordRecoveryProperties(
        Duration tokenTtl,
        int requestRateLimitMaxAttempts,
        Duration requestRateLimitWindow,
        int transportRateLimitMaxAttempts,
        Duration transportRateLimitWindow) {

    public PasswordRecoveryProperties {
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative() || tokenTtl.compareTo(Duration.ofDays(2)) > 0) {
            throw new IllegalArgumentException("merkon.password-recovery.token-ttl must be positive and no more than two days");
        }
        if (requestRateLimitMaxAttempts < 1 || requestRateLimitMaxAttempts > 100) {
            throw new IllegalArgumentException("merkon.password-recovery.request-rate-limit-max-attempts must be between 1 and 100");
        }
        if (requestRateLimitWindow == null || requestRateLimitWindow.isZero() || requestRateLimitWindow.isNegative()) {
            throw new IllegalArgumentException("merkon.password-recovery.request-rate-limit-window must be positive");
        }
        if (transportRateLimitMaxAttempts < 10 || transportRateLimitMaxAttempts > 100_000) {
            throw new IllegalArgumentException("merkon.password-recovery.transport-rate-limit-max-attempts must be between 10 and 100000");
        }
        if (transportRateLimitWindow == null || transportRateLimitWindow.isZero() || transportRateLimitWindow.isNegative()) {
            throw new IllegalArgumentException("merkon.password-recovery.transport-rate-limit-window must be positive");
        }
    }
}
