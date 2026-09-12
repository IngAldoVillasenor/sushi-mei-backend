package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.cardovia.merkon.backend.business.Business;
import com.cardovia.merkon.backend.business.BusinessMembership;
import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.BusinessRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** Proves that a runtime failure after JDBC/JPA destructive work rolls back the whole account deletion. */
@SpringBootTest
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, AccountDeletionRollbackIntegrationTest.TestInfrastructureConfiguration.class})
class AccountDeletionRollbackIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final String PASSWORD = "rollback account deletion password 2026";

    @Autowired private AccountDeletionService deletion;
    @Autowired private AppUserRepository users;
    @Autowired private BusinessRepository businesses;
    @Autowired private BusinessMembershipRepository memberships;
    @Autowired private AuthSessionService sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;

    @BeforeEach
    void clean() {
        jdbc.update("delete from public.security_audit_events");
        jdbc.update("delete from public.account_deletion_requests");
        jdbc.update("delete from public.auth_refresh_token_history");
        jdbc.update("delete from public.auth_sessions");
        jdbc.update("delete from public.password_reset_tokens");
        jdbc.update("delete from public.email_verification_tokens");
        jdbc.update("delete from public.user_terms_acceptances");
        memberships.deleteAll();
        users.deleteAll();
        businesses.findAll().stream().filter(business -> business.getLegacyKey() == null).forEach(businesses::delete);
    }

    @Test
    void unexpectedFailureAfterSessionAndMembershipCleanupRollsBackAllDatabaseState() {
        AppUser user = AppUser.create("rollback-user@example.com", "Rollback user", "stored-password-hash", ApplicationRole.OWNER, NOW);
        user.setNormalizedEmail("rollback-user@example.com", AccountRegistrationState.ACTIVE, NOW);
        user = users.saveAndFlush(user);
        Business business = businesses.saveAndFlush(Business.create("Rollback business", NOW));
        memberships.saveAndFlush(BusinessMembership.create(user, business, ApplicationRole.MANAGER, NOW));
        AppUser alternateOwner = AppUser.create("rollback-owner@example.com", "Rollback owner", "owner-hash", ApplicationRole.OWNER, NOW);
        alternateOwner.setNormalizedEmail("rollback-owner@example.com", AccountRegistrationState.ACTIVE, NOW);
        alternateOwner = users.saveAndFlush(alternateOwner);
        memberships.saveAndFlush(BusinessMembership.create(alternateOwner, business, ApplicationRole.OWNER, NOW));
        AuthSessionService.SessionToken session = sessions.open(user.getId(), user.getPasswordHash(), "rollback-session", null, null,
                business.getId(), "198.51.100.45");
        sessions.rotate(session.rawRefreshToken(), "rollback-session", "198.51.100.45");

        when(passwords.matches(eq(PASSWORD), eq("stored-password-hash"))).thenReturn(true);
        // AccountDeletionService only reaches encode after it has physically
        // removed sessions and memberships. Throwing here is a deterministic
        // rollback probe without a production-only test hook.
        when(passwords.encode(anyString())).thenThrow(new IllegalStateException("forced deletion rollback"));

        Long userId = user.getId();
        assertThatThrownBy(() -> deletion.confirmInApp(userId, PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced deletion rollback");

        AppUser restored = users.findById(userId).orElseThrow();
        assertThat(restored.getRegistrationState()).isNotEqualTo(AccountRegistrationState.DELETED);
        assertThat(restored.isActive()).isTrue();
        assertThat(restored.getEmail()).isEqualTo("rollback-user@example.com");
        assertThat(memberships.findByUserIdOrderByIdAsc(userId)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from public.auth_sessions where id = ?", Integer.class, session.session().getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from public.auth_refresh_token_history where session_id = ?", Integer.class, session.session().getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from public.account_deletion_requests where user_id = ?", Integer.class, userId))
                .isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean("rollbackPasswordEncoder") @Primary PasswordEncoder passwordEncoder() { return org.mockito.Mockito.mock(PasswordEncoder.class); }
        @Bean ChatModel chatModel() { return org.mockito.Mockito.mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return org.mockito.Mockito.mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() { return memoryId -> MessageWindowChatMemory.withMaxMessages(20); }
    }
}
