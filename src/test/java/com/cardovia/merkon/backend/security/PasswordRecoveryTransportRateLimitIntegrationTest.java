package com.cardovia.merkon.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "merkon.password-recovery.transport-rate-limit-max-attempts=10",
        "merkon.password-recovery.transport-rate-limit-window=15m"
})
@Import({SecurityTestKeyConfiguration.class,
        PasswordRecoveryTransportRateLimitIntegrationTest.TestInfrastructureConfiguration.class})
class PasswordRecoveryTransportRateLimitIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearBucketsAndAudits() {
        jdbcTemplate.update("delete from public.security_audit_events");
        jdbcTemplate.update("delete from public.registration_rate_limit_buckets");
    }

    @Test
    void malformedAndRandomConfirmRequestsConsumeTheSameUnspoofableCoarsePeerBudget() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-recovery/confirm")
                        .header("X-Forwarded-For", "198.51.100.10")
                        .contentType("application/json")
                        .content("{malformed"))
                .andExpect(status().isBadRequest());
        for (int index = 1; index < 10; index++) {
            mockMvc.perform(post("/api/v1/auth/password-recovery/confirm")
                            .header("X-Forwarded-For", "198.51.100." + index)
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(
                                    new PasswordRecoveryConfirmRequest("random-reset-token-" + index,
                                            "una frase larga segura recovery 2026"))))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/v1/auth/password-recovery/confirm")
                        .header("X-Forwarded-For", "203.0.113.250")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new PasswordRecoveryConfirmRequest("random-reset-token-limited",
                                        "una frase larga segura recovery 2026"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PASSWORD_RECOVERY_TRANSPORT_RATE_LIMITED"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from public.registration_rate_limit_buckets",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("select max(attempt_count) from public.registration_rate_limit_buckets",
                Integer.class)).isEqualTo(11);
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.security_audit_events "
                + "where event_type = 'PASSWORD_RECOVERY_REJECTED'", Integer.class)).isEqualTo(9);
    }

    @Test
    void uniqueUnknownEmailsCannotBypassTheCoarseTransportBudget() throws Exception {
        for (int index = 0; index < 10; index++) {
            mockMvc.perform(post("/api/v1/auth/password-recovery/request")
                            .header("X-Forwarded-For", "192.0.2." + index)
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(
                                    new PasswordRecoveryRequest("random-reset-" + index + "@example.com"))))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(post("/api/v1/auth/password-recovery/request")
                        .header("X-Forwarded-For", "203.0.113.99")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new PasswordRecoveryRequest("random-reset-limited@example.com"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PASSWORD_RECOVERY_TRANSPORT_RATE_LIMITED"));
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean ChatModel chatModel() { return org.mockito.Mockito.mock(ChatModel.class); }
        @Bean EmbeddingModel embeddingModel() { return org.mockito.Mockito.mock(EmbeddingModel.class); }
        @Bean ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
