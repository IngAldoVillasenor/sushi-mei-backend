package com.sushimei.sushimei.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SecurityDomainPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @Test
    void loginLockThresholdsAndSuccessfulResetFollowTheConfiguredPolicy() {
        AppUser user = AppUser.create("caja", "Caja", "{bcrypt}hash", ApplicationRole.CASHIER, NOW);

        recordFailures(user, 5);
        assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plusSeconds(30));

        recordFailures(user, 3);
        assertThat(user.getFailedLoginAttempts()).isEqualTo(8);
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plusSeconds(5 * 60L));

        recordFailures(user, 2);
        assertThat(user.getFailedLoginAttempts()).isEqualTo(10);
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plusSeconds(15 * 60L));

        user.recordSuccess(NOW.plusSeconds(1));
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void securityConfigurationRejectsUnsupportedOrUnsafeValues() {
        assertThatThrownBy(() -> properties(3, Duration.ofDays(15), Duration.ofMinutes(15), "kid", "issuer", "audience"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(32, Duration.ofDays(15), Duration.ofMinutes(15), "kid", "issuer", "audience"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(12, Duration.ZERO, Duration.ofMinutes(15), "kid", "issuer", "audience"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(12, Duration.ofDays(15), Duration.ZERO, "kid", "issuer", "audience"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(12, Duration.ofDays(15), Duration.ofMinutes(15), " ", "issuer", "audience"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(12, Duration.ofDays(15), Duration.ofMinutes(15), "kid", " ", "audience"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(12, Duration.ofDays(15), Duration.ofMinutes(15), "kid", "issuer", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void recordFailures(AppUser user, int count) {
        for (int index = 0; index < count; index++) {
            user.recordFailure(NOW);
        }
    }

    private static SushiMeiSecurityProperties properties(int bcryptStrength,
                                                         Duration sessionTtl,
                                                         Duration accessTokenTtl,
                                                         String keyId,
                                                         String issuer,
                                                         String audience) {
        return new SushiMeiSecurityProperties(
                new SushiMeiSecurityProperties.Jwt("", "", keyId, issuer, audience, accessTokenTtl),
                sessionTtl,
                bcryptStrength,
                null,
                null,
                null);
    }
}