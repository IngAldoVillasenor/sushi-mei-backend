package com.sushimei.sushimei.backend.conversation;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(ConversationSessionRepositoryTest.TestInfrastructureConfiguration.class)
class ConversationSessionRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-15T10:15:30Z");

    @Autowired
    private ConversationSessionRepository conversationSessionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAndReloadsAnOrderingSessionWithStringEnumsTimestampsAndVersion() throws NoSuchFieldException {
        ConversationSession saved = conversationSessionRepository.saveAndFlush(
                ConversationSession.create("525512345678", CREATED_AT));
        entityManager.clear();

        ConversationSession reloaded = conversationSessionRepository.findById("525512345678").orElseThrow();
        String persistedState = (String) entityManager.createNativeQuery(
                        "select state from conversation_sessions where phone_number = :phoneNumber")
                .setParameter("phoneNumber", "525512345678")
                .getSingleResult();

        assertThat(reloaded.getState()).isEqualTo(ConversationState.ORDERING);
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getLastActivityAt()).isEqualTo(CREATED_AT);
        assertThat(saved.getVersion()).isNotNull();
        assertThat(reloaded.getVersion()).isNotNull();
        assertThat(persistedState).isEqualTo("ORDERING");
        assertThat(ConversationSession.class.getDeclaredField("fulfillmentType")
                .getAnnotation(Enumerated.class).value()).isEqualTo(EnumType.STRING);
        assertThat(ConversationSession.class.getDeclaredField("paymentMethod")
                .getAnnotation(Enumerated.class).value()).isEqualTo(EnumType.STRING);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        @Bean
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return mock(EmbeddingModel.class);
        }

        @Bean
        ChatMemoryProvider chatMemoryProvider() {
            return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
        }
    }
}
