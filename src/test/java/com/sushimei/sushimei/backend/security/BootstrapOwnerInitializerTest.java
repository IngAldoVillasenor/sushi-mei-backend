package com.sushimei.sushimei.backend.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BootstrapOwnerInitializerTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordPolicyService passwords = mock(PasswordPolicyService.class);
    private final SecurityAuditService audit = mock(SecurityAuditService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void absentBootstrapCredentialsLeaveExistingUsersUntouched() {
        initializer(null, null).run(null);

        verifyNoInteractions(users, passwords, audit);
    }

    @Test
    void incompleteBootstrapCredentialsFailBeforeReadingOrChangingUsers() {
        assertThatThrownBy(() -> initializer("owner", null).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Both bootstrap owner credentials must be configured together");

        verifyNoInteractions(users, passwords, audit);
    }

    @Test
    void repeatedStartupDoesNotResetOrDuplicateAnExistingUserPopulation() {
        when(users.count()).thenReturn(1L);

        initializer("owner", "una frase larga segura 123").run(null);

        verify(users).count();
        verifyNoMoreInteractions(users);
        verifyNoInteractions(passwords, audit);
    }

    private BootstrapOwnerInitializer initializer(String username, String password) {
        SushiMeiSecurityProperties properties = new SushiMeiSecurityProperties(
                new SushiMeiSecurityProperties.Jwt("", "", "test-kid", "urn:test:issuer", "urn:test:audience",
                        Duration.ofMinutes(15)),
                Duration.ofDays(15), 4, username, password, null);
        return new BootstrapOwnerInitializer(properties, users, passwords, audit, clock);
    }
}
