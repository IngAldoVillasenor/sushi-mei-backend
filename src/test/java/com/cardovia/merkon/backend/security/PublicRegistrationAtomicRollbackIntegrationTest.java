package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cardovia.merkon.backend.business.BusinessMembershipRepository;
import com.cardovia.merkon.backend.business.BusinessRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import({SecurityTestKeyConfiguration.class, PublicRegistrationAtomicRollbackIntegrationTest.TestInfrastructureConfiguration.class})
class PublicRegistrationAtomicRollbackIntegrationTest {

    @Autowired private PublicRegistrationService registrations;
    @Autowired private TermsAcceptanceRepository failingTermsAcceptances;
    @Autowired private AppUserRepository users;
    @Autowired private BusinessRepository businesses;
    @Autowired private BusinessMembershipRepository memberships;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanFixtures() {
        jdbcTemplate.update("delete from public.security_audit_events");
        jdbcTemplate.update("delete from public.auth_refresh_token_history");
        jdbcTemplate.update("delete from public.auth_sessions");
        jdbcTemplate.update("delete from public.registration_rate_limit_buckets");
        memberships.deleteAll();
        users.deleteAll();
        businesses.findAll().stream()
                .filter(business -> business.getLegacyKey() == null)
                .forEach(businesses::delete);
        when(failingTermsAcceptances.saveAndFlush(any(TermsAcceptance.class)))
                .thenThrow(new DataIntegrityViolationException("forced terms persistence failure"));
    }

    @Test
    void termsPersistenceFailureRollsBackUserBusinessMembershipAndRegistrationAuditTogether() {
        assertThatThrownBy(() -> registrations.register(new PublicRegistrationRequest(
                "rollback@example.com", "Rollback", "una frase larga segura 123", "Rollback business", true),
                "198.51.100.51"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(users.findByEmail("rollback@example.com")).isEmpty();
        assertThat(businesses.findAll().stream().filter(business -> business.getLegacyKey() == null)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.business_memberships", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.security_audit_events where event_type = 'REGISTRATION_ACCEPTED'",
                Integer.class)).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean @Primary TermsAcceptanceRepository failingTermsAcceptanceRepository() {
            return mock(TermsAcceptanceRepository.class);
        }

        @Bean ChatModel chatModel() { return mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
