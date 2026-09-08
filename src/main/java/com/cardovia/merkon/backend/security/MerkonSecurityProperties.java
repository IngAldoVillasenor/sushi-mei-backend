package com.cardovia.merkon.backend.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "merkon.security")
public record MerkonSecurityProperties(
        Jwt jwt,
        Duration sessionTtl,
        int bcryptStrength,
        String bootstrapOwnerUsername,
        String bootstrapOwnerPassword,
        String bootstrapOwnerDisplayName) {

    public MerkonSecurityProperties {
        if (jwt == null) {
            jwt = new Jwt("", "", "merkon-primary", "urn:merkon:auth", "urn:merkon:api", Duration.ofMinutes(15));
        }
        if (sessionTtl == null) {
            sessionTtl = Duration.ofDays(15);
        }
        if (bcryptStrength == 0) {
            bcryptStrength = 12;
        }
        if (bcryptStrength < 4 || bcryptStrength > 31) {
            throw new IllegalArgumentException("merkon.security.bcrypt-strength must be between 4 and 31");
        }
        if (sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("merkon.security.session-ttl must be positive");
        }
        jwt.validate();
    }

    public record Jwt(
            String privateKeyLocation,
            String publicKeyLocation,
            String keyId,
            String issuer,
            String audience,
            Duration accessTokenTtl) {

        void validate() {
            if (isBlank(keyId) || isBlank(issuer) || isBlank(audience)) {
                throw new IllegalArgumentException("JWT key ID, issuer, and audience must be configured");
            }
            if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
                throw new IllegalArgumentException("merkon.security.jwt.access-token-ttl must be positive");
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
