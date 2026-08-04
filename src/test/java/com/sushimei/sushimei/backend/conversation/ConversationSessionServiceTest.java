package com.sushimei.sushimei.backend.conversation;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import({ConversationSessionServiceTest.TestInfrastructureConfiguration.class,
        ConversationSessionServiceTest.FixedClockConfiguration.class})
class ConversationSessionServiceTest {

    private static final String PHONE_NUMBER = "525512345678";
    private static final Instant CREATED_AT = Instant.parse("2026-01-15T10:15:30Z");
    private static final Instant SERVICE_TIME = Instant.parse("2026-01-15T11:30:00Z");

    @Autowired
    private ConversationSessionService conversationSessionService;

    @Autowired
    private ConversationSessionRepository conversationSessionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void getOrCreateSessionReturnsTheSamePersistentSessionForTheSamePhoneNumber() {
        ConversationSession first = conversationSessionService.getOrCreateSession("  " + PHONE_NUMBER + "  ");
        entityManager.flush();
        ConversationSession second = conversationSessionService.getOrCreateSession(PHONE_NUMBER);
        entityManager.flush();

        assertThat(first.getPhoneNumber()).isEqualTo(PHONE_NUMBER);
        assertThat(second.getPhoneNumber()).isEqualTo(PHONE_NUMBER);
        assertThat(conversationSessionRepository.count()).isEqualTo(1);
    }

    @Test
    void recordInboundActivityUpdatesActivityWithoutChangingState() {
        persistSessionCreatedAt(CREATED_AT);

        ConversationSession session = conversationSessionService.recordInboundActivity(PHONE_NUMBER);
        entityManager.flush();

        assertThat(session.getState()).isEqualTo(ConversationState.ORDERING);
        assertThat(session.getLastActivityAt()).isEqualTo(SERVICE_TIME);
        assertThat(session.getUpdatedAt()).isEqualTo(SERVICE_TIME);
    }

    @Test
    void recordTransferReceiptStoresPathWithoutChangingState() {
        persistSessionCreatedAt(CREATED_AT);

        ConversationSession session = conversationSessionService.recordTransferReceipt(PHONE_NUMBER, "receipts/payment.jpg");
        entityManager.flush();

        assertThat(session.getTransferReceiptPath()).isEqualTo("receipts/payment.jpg");
        assertThat(session.getState()).isEqualTo(ConversationState.ORDERING);
        assertThat(session.getLastActivityAt()).isEqualTo(SERVICE_TIME);
    }

    @Test
    void resetPreservesCreationTimeAndClearsCheckoutFields() {
        ConversationSession session = ConversationSession.create(PHONE_NUMBER, CREATED_AT);
        Instant checkoutTime = CREATED_AT.plusSeconds(60);
        session.selectDelivery(checkoutTime);
        session.captureDeliveryAddress("Calle 1", checkoutTime);
        session.selectCash(checkoutTime);
        session.captureCashDenomination(new BigDecimal("500.00"), checkoutTime);
        session.recordTransferReceipt("receipts/old.jpg", CREATED_AT.plusSeconds(60));
        conversationSessionRepository.saveAndFlush(session);
        Long persistedVersion = session.getVersion();
        entityManager.clear();

        ConversationSession resetSession = conversationSessionService.resetSession(PHONE_NUMBER);
        entityManager.flush();

        assertThat(resetSession.getPhoneNumber()).isEqualTo(PHONE_NUMBER);
        assertThat(resetSession.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(resetSession.getVersion()).isGreaterThan(persistedVersion);
        assertThat(resetSession.getState()).isEqualTo(ConversationState.ORDERING);
        assertThat(resetSession.getFulfillmentType()).isNull();
        assertThat(resetSession.getDeliveryAddress()).isNull();
        assertThat(resetSession.getPickupName()).isNull();
        assertThat(resetSession.getPaymentMethod()).isNull();
        assertThat(resetSession.getCashDenomination()).isNull();
        assertThat(resetSession.getTransferReceiptPath()).isNull();
        assertThat(resetSession.getUpdatedAt()).isEqualTo(SERVICE_TIME);
        assertThat(resetSession.getLastActivityAt()).isEqualTo(SERVICE_TIME);
    }

    @Test
    void rejectsNullAndBlankPhoneNumbersAndReceiptPaths() {
        assertThatIllegalArgumentException().isThrownBy(() -> conversationSessionService.getOrCreateSession(null));
        assertThatIllegalArgumentException().isThrownBy(() -> conversationSessionService.getOrCreateSession("   "));
        assertThatIllegalArgumentException().isThrownBy(() -> conversationSessionService.recordTransferReceipt(PHONE_NUMBER, null));
        assertThatIllegalArgumentException().isThrownBy(() -> conversationSessionService.recordTransferReceipt(PHONE_NUMBER, "   "));
    }

    private void persistSessionCreatedAt(Instant createdAt) {
        conversationSessionRepository.saveAndFlush(ConversationSession.create(PHONE_NUMBER, createdAt));
        entityManager.clear();
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

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(SERVICE_TIME, ZoneOffset.UTC);
        }
    }
}
