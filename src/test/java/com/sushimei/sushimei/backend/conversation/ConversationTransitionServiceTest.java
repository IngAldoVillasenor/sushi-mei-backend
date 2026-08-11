package com.sushimei.sushimei.backend.conversation;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({com.sushimei.sushimei.backend.security.SecurityTestKeyConfiguration.class, ConversationTransitionServiceTest.TestInfrastructureConfiguration.class,
        ConversationTransitionServiceTest.FixedClockConfiguration.class})
class ConversationTransitionServiceTest {

    private static final Instant SERVICE_TIME = Instant.parse("2026-02-15T11:30:00Z");

    @Autowired
    private ConversationTransitionService conversationTransitionService;

    @Autowired
    private ConversationSessionService conversationSessionService;

    @Autowired
    private ConversationSessionRepository conversationSessionRepository;

    @BeforeEach
    void clearSessions() {
        conversationSessionRepository.deleteAll();
    }

    @AfterEach
    void clearSessionsAfterTest() {
        conversationSessionRepository.deleteAll();
    }

    @Test
    void onlyRequestCheckoutReviewCreatesAMissingSessionAndTrimsThePhoneNumber() {
        assertThatThrownBy(() -> conversationTransitionService.confirmCart("525512345678"))
                .isInstanceOf(ConversationSessionNotFoundException.class);
        assertThatThrownBy(() -> conversationTransitionService.selectDelivery("525512345678"))
                .isInstanceOf(ConversationSessionNotFoundException.class);
        assertThatThrownBy(() -> conversationTransitionService.cancelCheckout("525512345678"))
                .isInstanceOf(ConversationSessionNotFoundException.class);
        assertThat(conversationSessionRepository.count()).isZero();

        ConversationSession created = conversationTransitionService.requestCheckoutReview(" 525512345678 ");

        assertThat(created.getPhoneNumber()).isEqualTo("525512345678");
        assertThat(created.getState()).isEqualTo(ConversationState.WAITING_CART_CONFIRMATION);
        assertThat(created.getCreatedAt()).isEqualTo(SERVICE_TIME);
        assertThat(created.getUpdatedAt()).isEqualTo(SERVICE_TIME);
        assertThat(created.getLastActivityAt()).isEqualTo(SERVICE_TIME);
        assertThat(conversationSessionRepository.count()).isEqualTo(1);
    }

    @Test
    void completesDeliveryCashAndDeliveryTransferPaths() {
        String cashPhone = "525512345601";
        startDelivery(cashPhone);
        conversationTransitionService.selectCash(cashPhone);
        ConversationSession cashReady = conversationTransitionService.provideCashDenomination(cashPhone,
                new BigDecimal("250.00"));

        assertThat(cashReady.getState()).isEqualTo(ConversationState.READY_TO_CONFIRM);
        assertThat(cashReady.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(cashReady.getCashDenomination()).isEqualByComparingTo("250.00");
        assertThat(cashReady.getCreatedAt()).isEqualTo(SERVICE_TIME);

        String transferPhone = "525512345602";
        startDelivery(transferPhone);
        conversationTransitionService.selectTransfer(transferPhone);
        ConversationSession transferReady = conversationTransitionService.provideTransferReceipt(transferPhone,
                " receipts/transfer.jpg ");

        assertThat(transferReady.getState()).isEqualTo(ConversationState.READY_TO_CONFIRM);
        assertThat(transferReady.getPaymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
        assertThat(transferReady.getTransferReceiptPath()).isEqualTo("receipts/transfer.jpg");
        assertThat(transferReady.getCashDenomination()).isNull();
    }

    @Test
    void completesPickupCashPickupTransferAndPickupCardPaths() {
        String cashPhone = "525512345603";
        startPickup(cashPhone);
        conversationTransitionService.selectCash(cashPhone);
        ConversationSession cashReady = conversationTransitionService.provideCashDenomination(cashPhone,
                new BigDecimal("100"));
        assertThat(cashReady.getState()).isEqualTo(ConversationState.READY_TO_CONFIRM);
        assertThat(cashReady.getCashDenomination()).isEqualByComparingTo("100.00");

        String transferPhone = "525512345604";
        startPickup(transferPhone);
        conversationTransitionService.selectTransfer(transferPhone);
        ConversationSession transferReady = conversationTransitionService.provideTransferReceipt(transferPhone,
                "receipts/pickup.jpg");
        assertThat(transferReady.getState()).isEqualTo(ConversationState.READY_TO_CONFIRM);
        assertThat(transferReady.getPaymentMethod()).isEqualTo(PaymentMethod.TRANSFER);

        String cardPhone = "525512345605";
        startPickup(cardPhone);
        ConversationSession cardReady = conversationTransitionService.selectCard(cardPhone);
        assertThat(cardReady.getState()).isEqualTo(ConversationState.READY_TO_CONFIRM);
        assertThat(cardReady.getPaymentMethod()).isEqualTo(PaymentMethod.CARD);
    }

    @Test
    void deliveryCardAndInvalidCommandsRollBackWithoutIncrementingVersion() {
        String phoneNumber = "525512345606";
        startDelivery(phoneNumber);
        ConversationSession before = requiredSession(phoneNumber);
        Long versionBefore = before.getVersion();

        assertThatThrownBy(() -> conversationTransitionService.selectCard(phoneNumber))
                .isInstanceOf(InvalidConversationTransitionException.class);

        ConversationSession after = requiredSession(phoneNumber);
        assertThat(after.getState()).isEqualTo(ConversationState.WAITING_PAYMENT_METHOD);
        assertThat(after.getPaymentMethod()).isNull();
        assertThat(after.getVersion()).isEqualTo(versionBefore);
        assertThat(after.getCreatedAt()).isEqualTo(SERVICE_TIME);
        assertThat(after.getUpdatedAt()).isEqualTo(SERVICE_TIME);
        assertThat(after.getLastActivityAt()).isEqualTo(SERVICE_TIME);
    }

    @Test
    void successfulCommandsIncrementTheOptimisticLockVersionAcrossTransactions() {
        String phoneNumber = "525512345607";
        ConversationSession requested = conversationTransitionService.requestCheckoutReview(phoneNumber);
        Long requestedVersion = requiredSession(phoneNumber).getVersion();

        conversationTransitionService.confirmCart(phoneNumber);
        Long confirmedCartVersion = requiredSession(phoneNumber).getVersion();

        conversationTransitionService.selectPickup(phoneNumber);
        Long selectedPickupVersion = requiredSession(phoneNumber).getVersion();

        assertThat(requested.getVersion()).isNotNull();
        assertThat(confirmedCartVersion).isGreaterThan(requestedVersion);
        assertThat(selectedPickupVersion).isGreaterThan(confirmedCartVersion);
    }

    @Test
    void terminalStatesPersistAndResetRemainsCompatibleAfterCancellationAndConfirmation() {
        String cancelledPhone = "525512345608";
        ConversationSession cancelled = conversationTransitionService.requestCheckoutReview(cancelledPhone);
        conversationTransitionService.cancelCheckout(cancelledPhone);
        assertThat(requiredSession(cancelledPhone).getState()).isEqualTo(ConversationState.CANCELLED);

        ConversationSession resetCancelled = conversationSessionService.resetSession(cancelledPhone);
        assertReset(resetCancelled, cancelled.getCreatedAt());

        String confirmedPhone = "525512345609";
        startPickup(confirmedPhone);
        conversationTransitionService.selectCard(confirmedPhone);
        ConversationSession confirmed = conversationTransitionService.confirmCheckout(confirmedPhone);
        assertThat(requiredSession(confirmedPhone).getState()).isEqualTo(ConversationState.ORDER_CONFIRMED);

        ConversationSession resetConfirmed = conversationSessionService.resetSession(confirmedPhone);
        assertReset(resetConfirmed, confirmed.getCreatedAt());
    }

    @Test
    void validationRejectsNullAndBlankPhoneNumbersWithoutCreatingSessions() {
        assertThatThrownBy(() -> conversationTransitionService.requestCheckoutReview(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversationTransitionService.requestCheckoutReview("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(conversationSessionRepository.count()).isZero();
    }

    private void startDelivery(String phoneNumber) {
        conversationTransitionService.requestCheckoutReview(phoneNumber);
        conversationTransitionService.confirmCart(phoneNumber);
        conversationTransitionService.selectDelivery(phoneNumber);
        conversationTransitionService.provideDeliveryAddress(phoneNumber, "Calle 123");
    }

    private void startPickup(String phoneNumber) {
        conversationTransitionService.requestCheckoutReview(phoneNumber);
        conversationTransitionService.confirmCart(phoneNumber);
        conversationTransitionService.selectPickup(phoneNumber);
        conversationTransitionService.providePickupName(phoneNumber, "Li");
    }

    private ConversationSession requiredSession(String phoneNumber) {
        return conversationSessionRepository.findById(phoneNumber).orElseThrow();
    }

    private void assertReset(ConversationSession session, Instant createdAt) {
        assertThat(session.getState()).isEqualTo(ConversationState.ORDERING);
        assertThat(session.getFulfillmentType()).isNull();
        assertThat(session.getDeliveryAddress()).isNull();
        assertThat(session.getPickupName()).isNull();
        assertThat(session.getPaymentMethod()).isNull();
        assertThat(session.getCashDenomination()).isNull();
        assertThat(session.getTransferReceiptPath()).isNull();
        assertThat(session.getCreatedAt()).isEqualTo(createdAt);
        assertThat(session.getUpdatedAt()).isEqualTo(SERVICE_TIME);
        assertThat(session.getLastActivityAt()).isEqualTo(SERVICE_TIME);
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
