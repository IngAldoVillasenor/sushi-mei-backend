package com.cardovia.merkon.backend.security;

import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.LegacyBusinessResolver;
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
    private final LegacyBusinessResolver legacyBusiness = mock(LegacyBusinessResolver.class);
    private final BusinessMembershipRepository memberships = mock(BusinessMembershipRepository.class);
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

    @Test
    void firstBootstrapCreatesTheOwnerMembershipInTheExplicitLegacyBusiness() {
        when(users.count()).thenReturn(0L);
        when(passwords.encodeValidated("owner", "una frase larga segura 123")).thenReturn("{bcrypt}hash");
        when(legacyBusiness.requireLegacyBusiness()).thenReturn(Business.create("Legacy business", clock.instant()));

        initializer("owner", "una frase larga segura 123").run(null);

        verify(users).saveAndFlush(org.mockito.ArgumentMatchers.any(AppUser.class));
        verify(memberships).save(org.mockito.ArgumentMatchers.any());
    }

    private BootstrapOwnerInitializer initializer(String username, String password) {
        MerkonSecurityProperties properties = new MerkonSecurityProperties(
                new MerkonSecurityProperties.Jwt("", "", "test-kid", "urn:test:issuer", "urn:test:audience",
                        Duration.ofMinutes(15)),
                Duration.ofDays(15), 4, username, password, null);
        return new BootstrapOwnerInitializer(properties, users, legacyBusiness, memberships, passwords, audit, clock);
    }
}
