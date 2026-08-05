package com.sushimei.sushimei.backend.conversation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationStateMachineTest {

    private static final String PHONE_NUMBER = "525512345678";
    private static final Instant CREATED_AT = Instant.parse("2026-02-01T10:00:00Z");
    private static final Instant TRANSITION_TIME = Instant.parse("2026-02-01T10:05:00Z");

    private final ConversationStateMachine stateMachine = new ConversationStateMachine();

    @Test
    void nullTimestampIsRejectedBeforeAnySessionMutation() {
        ConversationSession session = newSession();
        Snapshot before = Snapshot.from(session);

        assertThatThrownBy(() -> stateMachine.requestCheckoutReview(session, null))
                .isInstanceOf(NullPointerException.class);

        assertThat(Snapshot.from(session)).isEqualTo(before);
    }
    @Test
    void followsTheDeliveryCashPathWithOneSuppliedTimestampPerTransition() {
        ConversationSession session = newSession();

        stateMachine.requestCheckoutReview(session, TRANSITION_TIME);
        stateMachine.confirmCart(session, TRANSITION_TIME);
        stateMachine.selectDelivery(session, TRANSITION_TIME);
        stateMachine.provideDeliveryAddress(session, "  Calle 123  ", TRANSITION_TIME);
        stateMachine.selectCash(session, TRANSITION_TIME);
        stateMachine.provideCashDenomination(session, new BigDecimal("500"), TRANSITION_TIME);
        stateMachine.confirmCheckout(session, TRANSITION_TIME);

        assertThat(session.getState()).isEqualTo(ConversationState.ORDER_CONFIRMED);
        assertThat(session.getFulfillmentType()).isEqualTo(FulfillmentType.DELIVERY);
        assertThat(session.getDeliveryAddress()).isEqualTo("Calle 123");
        assertThat(session.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(session.getCashDenomination()).isEqualByComparingTo("500.00");
        assertThat(session.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(session.getUpdatedAt()).isEqualTo(TRANSITION_TIME);
        assertThat(session.getLastActivityAt()).isEqualTo(TRANSITION_TIME);
    }

    @Test
    void confirmedCartCanReturnToOrdering() {
        ConversationSession session = newSession();
        stateMachine.requestCheckoutReview(session, TRANSITION_TIME);

        stateMachine.continueOrdering(session, TRANSITION_TIME);

        assertThat(session.getState()).isEqualTo(ConversationState.ORDERING);
        assertThat(session.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(session.getUpdatedAt()).isEqualTo(TRANSITION_TIME);
        assertThat(session.getLastActivityAt()).isEqualTo(TRANSITION_TIME);
    }

    @Test
    void followsThePickupTransferPath() {
        ConversationSession session = pickupWaitingForPayment();

        stateMachine.selectTransfer(session, TRANSITION_TIME);
        stateMachine.provideTransferReceipt(session, "  receipts/transfer.jpg  ", TRANSITION_TIME);

        assertThat(session.getState()).isEqualTo(ConversationState.READY_TO_CONFIRM);
        assertThat(session.getFulfillmentType()).isEqualTo(FulfillmentType.PICKUP);
        assertThat(session.getPickupName()).isEqualTo("Li");
        assertThat(session.getPaymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
        assertThat(session.getTransferReceiptPath()).isEqualTo("receipts/transfer.jpg");
        assertThat(session.getCashDenomination()).isNull();
    }

    @Test
    void cardSucceedsForPickupAndClearsOtherPaymentBranchData() {
        ConversationSession session = pickupWaitingForPayment();
        session.recordTransferReceipt("receipts/shadow.jpg", CREATED_AT.plusSeconds(1));

        stateMachine.selectCard(session, TRANSITION_TIME);

        assertThat(session.getState()).isEqualTo(ConversationState.READY_TO_CONFIRM);
        assertThat(session.getPaymentMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(session.getCashDenomination()).isNull();
        assertThat(session.getTransferReceiptPath()).isNull();
    }

    @Test
    void cardIsRejectedForDeliveryWithoutMutation() {
        ConversationSession session = deliveryWaitingForPayment();

        assertInvalidAndUnchanged(session, candidate -> stateMachine.selectCard(candidate, TRANSITION_TIME),
                ConversationTransitionAction.SELECT_CARD);
    }

    @Test
    void deliveryAndPickupSelectionClearTheOtherFulfillmentBranchData() {
        ConversationSession deliverySession = newSession();
        stateMachine.requestCheckoutReview(deliverySession, TRANSITION_TIME);
        stateMachine.confirmCart(deliverySession, TRANSITION_TIME);
        deliverySession.recordTransferReceipt("receipts/shadow.jpg", TRANSITION_TIME);
        forceField(deliverySession, "pickupName", "Old pickup");

        stateMachine.selectDelivery(deliverySession, TRANSITION_TIME);

        assertThat(deliverySession.getFulfillmentType()).isEqualTo(FulfillmentType.DELIVERY);
        assertThat(deliverySession.getPickupName()).isNull();

        ConversationSession pickupSession = newSession();
        stateMachine.requestCheckoutReview(pickupSession, TRANSITION_TIME);
        stateMachine.confirmCart(pickupSession, TRANSITION_TIME);
        forceField(pickupSession, "deliveryAddress", "Old address");

        stateMachine.selectPickup(pickupSession, TRANSITION_TIME);

        assertThat(pickupSession.getFulfillmentType()).isEqualTo(FulfillmentType.PICKUP);
        assertThat(pickupSession.getDeliveryAddress()).isNull();
    }

    @Test
    void cashAndTransferSelectionClearTheOtherPaymentBranchData() {
        ConversationSession cashSession = deliveryWaitingForPayment();
        cashSession.recordTransferReceipt("receipts/old.jpg", TRANSITION_TIME);

        stateMachine.selectCash(cashSession, TRANSITION_TIME);

        assertThat(cashSession.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(cashSession.getTransferReceiptPath()).isNull();

        ConversationSession transferSession = deliveryWaitingForPayment();
        forceField(transferSession, "cashDenomination", new BigDecimal("250.00"));

        stateMachine.selectTransfer(transferSession, TRANSITION_TIME);

        assertThat(transferSession.getPaymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
        assertThat(transferSession.getCashDenomination()).isNull();
    }

    @Test
    void everyActionRejectsAnIncorrectRepresentativeSourceStateWithoutMutation() {
        List<InvalidAction> invalidActions = List.of(
                new InvalidAction(this::waitingForCartConfirmation, session -> stateMachine.requestCheckoutReview(session, TRANSITION_TIME)),
                new InvalidAction(this::newSession, session -> stateMachine.confirmCart(session, TRANSITION_TIME)),
                new InvalidAction(this::newSession, session -> stateMachine.continueOrdering(session, TRANSITION_TIME)),
                new InvalidAction(this::newSession, session -> stateMachine.selectDelivery(session, TRANSITION_TIME)),
                new InvalidAction(this::newSession, session -> stateMachine.selectPickup(session, TRANSITION_TIME)),
                new InvalidAction(this::newSession, session -> stateMachine.provideDeliveryAddress(session, "Calle 123", TRANSITION_TIME)),
                new InvalidAction(this::newSession, session -> stateMachine.providePickupName(session, "Li", TRANSITION_TIME)),
                new InvalidAction(this::newSession, session -> stateMachine.selectCash(session, TRANSITION_TIME)),
                new InvalidAction(this::newSession, session -> stateMachine.selectTransfer(session, TRANSITION_TIME)),
                new InvalidAction(this::newSession, session -> stateMachine.selectCard(session, TRANSITION_TIME)),
                new InvalidAction(this::newSession, session -> stateMachine.provideCashDenomination(session, BigDecimal.ONE, TRANSITION_TIME)),
                new InvalidAction(this::newSession, session -> stateMachine.provideTransferReceipt(session, "receipts/a.jpg", TRANSITION_TIME)),
                new InvalidAction(this::newSession, session -> stateMachine.confirmCheckout(session, TRANSITION_TIME)),
                new InvalidAction(this::confirmedSession, session -> stateMachine.cancelCheckout(session, TRANSITION_TIME)));

        for (InvalidAction invalidAction : invalidActions) {
            ConversationSession session = invalidAction.sessionFactory().get();
            Snapshot before = Snapshot.from(session);

            assertThatThrownBy(() -> invalidAction.command().accept(session))
                    .isInstanceOf(InvalidConversationTransitionException.class);

            assertThat(Snapshot.from(session)).isEqualTo(before);
        }
    }

    @Test
    void terminalStatesRejectOrdinaryCheckoutTransitions() {
        ConversationSession cancelled = newSession();
        stateMachine.cancelCheckout(cancelled, TRANSITION_TIME);
        assertInvalidAndUnchanged(cancelled, session -> stateMachine.requestCheckoutReview(session, TRANSITION_TIME),
                ConversationTransitionAction.REQUEST_CHECKOUT_REVIEW);

        ConversationSession confirmed = confirmedSession();
        assertInvalidAndUnchanged(confirmed, session -> stateMachine.selectDelivery(session, TRANSITION_TIME),
                ConversationTransitionAction.SELECT_DELIVERY);
    }

    @Test
    void validationFailuresDoNotPartiallyMutateSessions() {
        assertInvalidAndUnchanged(deliveryWaitingForAddress(),
                session -> stateMachine.provideDeliveryAddress(session, null, TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_DELIVERY_ADDRESS);
        assertInvalidAndUnchanged(deliveryWaitingForAddress(),
                session -> stateMachine.provideDeliveryAddress(session, "   ", TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_DELIVERY_ADDRESS);
        assertInvalidAndUnchanged(deliveryWaitingForAddress(),
                session -> stateMachine.provideDeliveryAddress(session, "Call", TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_DELIVERY_ADDRESS);
        assertInvalidAndUnchanged(deliveryWaitingForAddress(),
                session -> stateMachine.provideDeliveryAddress(session, "x".repeat(501), TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_DELIVERY_ADDRESS);

        assertInvalidAndUnchanged(pickupWaitingForName(),
                session -> stateMachine.providePickupName(session, null, TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_PICKUP_NAME);
        assertInvalidAndUnchanged(pickupWaitingForName(),
                session -> stateMachine.providePickupName(session, " ", TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_PICKUP_NAME);
        assertInvalidAndUnchanged(pickupWaitingForName(),
                session -> stateMachine.providePickupName(session, "A", TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_PICKUP_NAME);
        assertInvalidAndUnchanged(pickupWaitingForName(),
                session -> stateMachine.providePickupName(session, "x".repeat(121), TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_PICKUP_NAME);

        assertInvalidAndUnchanged(deliveryWaitingForCash(),
                session -> stateMachine.provideCashDenomination(session, null, TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_CASH_DENOMINATION);
        assertInvalidAndUnchanged(deliveryWaitingForCash(),
                session -> stateMachine.provideCashDenomination(session, BigDecimal.ZERO, TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_CASH_DENOMINATION);
        assertInvalidAndUnchanged(deliveryWaitingForCash(),
                session -> stateMachine.provideCashDenomination(session, new BigDecimal("-1.00"), TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_CASH_DENOMINATION);
        assertInvalidAndUnchanged(deliveryWaitingForCash(),
                session -> stateMachine.provideCashDenomination(session, new BigDecimal("1.001"), TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_CASH_DENOMINATION);
        assertInvalidAndUnchanged(deliveryWaitingForCash(),
                session -> stateMachine.provideCashDenomination(session, new BigDecimal("999999999999999999.99"), TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_CASH_DENOMINATION);

        assertInvalidAndUnchanged(deliveryWaitingForTransfer(),
                session -> stateMachine.provideTransferReceipt(session, null, TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_TRANSFER_RECEIPT);
        assertInvalidAndUnchanged(deliveryWaitingForTransfer(),
                session -> stateMachine.provideTransferReceipt(session, "  ", TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_TRANSFER_RECEIPT);
        assertInvalidAndUnchanged(deliveryWaitingForTransfer(),
                session -> stateMachine.provideTransferReceipt(session, "x".repeat(1025), TRANSITION_TIME),
                ConversationTransitionAction.PROVIDE_TRANSFER_RECEIPT);
    }

    @Test
    void acceptsBoundaryLengthStringsAndExactlyRepresentableCashScale() {
        ConversationSession minimumAddressSession = deliveryWaitingForAddress();
        stateMachine.provideDeliveryAddress(minimumAddressSession, "Calle", TRANSITION_TIME);
        assertThat(minimumAddressSession.getDeliveryAddress()).isEqualTo("Calle");

        ConversationSession maximumAddressSession = deliveryWaitingForAddress();
        stateMachine.provideDeliveryAddress(maximumAddressSession, "x".repeat(500), TRANSITION_TIME);
        assertThat(maximumAddressSession.getDeliveryAddress()).hasSize(500);

        ConversationSession minimumPickupSession = pickupWaitingForName();
        stateMachine.providePickupName(minimumPickupSession, "Li", TRANSITION_TIME);
        assertThat(minimumPickupSession.getPickupName()).isEqualTo("Li");

        ConversationSession maximumPickupSession = pickupWaitingForName();
        stateMachine.providePickupName(maximumPickupSession, "x".repeat(120), TRANSITION_TIME);
        assertThat(maximumPickupSession.getPickupName()).hasSize(120);

        ConversationSession cashSession = deliveryWaitingForCash();
        stateMachine.provideCashDenomination(cashSession, new BigDecimal("1.2"), TRANSITION_TIME);
        assertThat(cashSession.getCashDenomination()).isEqualByComparingTo("1.20");

        ConversationSession maximumCashSession = deliveryWaitingForCash();
        stateMachine.provideCashDenomination(maximumCashSession, new BigDecimal("99999999999999999.99"), TRANSITION_TIME);
        assertThat(maximumCashSession.getCashDenomination()).isEqualByComparingTo("99999999999999999.99");

        ConversationSession minimumReceiptSession = deliveryWaitingForTransfer();
        stateMachine.provideTransferReceipt(minimumReceiptSession, "x", TRANSITION_TIME);
        assertThat(minimumReceiptSession.getTransferReceiptPath()).isEqualTo("x");

        ConversationSession maximumReceiptSession = deliveryWaitingForTransfer();
        stateMachine.provideTransferReceipt(maximumReceiptSession, "x".repeat(1024), TRANSITION_TIME);
        assertThat(maximumReceiptSession.getTransferReceiptPath()).hasSize(1024);
    }

    @Test
    void malformedReadySessionsFailCentralInvariantsWithoutMutation() {
        ConversationSession deliveryCash = deliveryWaitingForCash();
        stateMachine.provideCashDenomination(deliveryCash, new BigDecimal("50.00"), TRANSITION_TIME);
        forceField(deliveryCash, "cashDenomination", BigDecimal.ZERO);

        assertInvalidAndUnchanged(deliveryCash, session -> stateMachine.confirmCheckout(session, TRANSITION_TIME),
                ConversationTransitionAction.CONFIRM_CHECKOUT);

        ConversationSession pickupCard = pickupWaitingForPayment();
        stateMachine.selectCard(pickupCard, TRANSITION_TIME);
        forceField(pickupCard, "fulfillmentType", FulfillmentType.DELIVERY);
        forceField(pickupCard, "deliveryAddress", "Calle 123");

        assertInvalidAndUnchanged(pickupCard, session -> stateMachine.confirmCheckout(session, TRANSITION_TIME),
                ConversationTransitionAction.CONFIRM_CHECKOUT);

        ConversationSession pickupCardWithoutName = pickupWaitingForPayment();
        stateMachine.selectCard(pickupCardWithoutName, TRANSITION_TIME);
        forceField(pickupCardWithoutName, "pickupName", " ");

        assertInvalidAndUnchanged(pickupCardWithoutName,
                session -> stateMachine.confirmCheckout(session, TRANSITION_TIME),
                ConversationTransitionAction.CONFIRM_CHECKOUT);
    }

    @Test
    void shadowReceiptDoesNotAdvanceAWorkflowWithoutAValidStrictCommand() {
        ConversationSession session = newSession();
        session.recordTransferReceipt("receipts/shadow.jpg", TRANSITION_TIME);
        Snapshot before = Snapshot.from(session);

        assertThatThrownBy(() -> stateMachine.provideTransferReceipt(session, "receipts/strict.jpg", TRANSITION_TIME))
                .isInstanceOf(InvalidConversationTransitionException.class);

        assertThat(session.getState()).isEqualTo(ConversationState.ORDERING);
        assertThat(Snapshot.from(session)).isEqualTo(before);
    }

    @Test
    void cancellationPreservesFieldsAndResetWorksAfterBothTerminalStates() {
        ConversationSession ready = deliveryWaitingForCash();
        stateMachine.provideCashDenomination(ready, new BigDecimal("100.00"), TRANSITION_TIME);
        stateMachine.cancelCheckout(ready, TRANSITION_TIME);

        assertThat(ready.getState()).isEqualTo(ConversationState.CANCELLED);
        assertThat(ready.getCashDenomination()).isEqualByComparingTo("100.00");
        ready.reset(TRANSITION_TIME);
        assertReset(ready);

        ConversationSession confirmed = confirmedSession();
        confirmed.reset(TRANSITION_TIME);
        assertReset(confirmed);
    }

    private ConversationSession newSession() {
        return ConversationSession.create(PHONE_NUMBER, CREATED_AT);
    }

    private ConversationSession waitingForCartConfirmation() {
        ConversationSession session = newSession();
        stateMachine.requestCheckoutReview(session, TRANSITION_TIME);
        return session;
    }

    private ConversationSession deliveryWaitingForAddress() {
        ConversationSession session = waitingForCartConfirmation();
        stateMachine.confirmCart(session, TRANSITION_TIME);
        stateMachine.selectDelivery(session, TRANSITION_TIME);
        return session;
    }

    private ConversationSession deliveryWaitingForPayment() {
        ConversationSession session = deliveryWaitingForAddress();
        stateMachine.provideDeliveryAddress(session, "Calle 123", TRANSITION_TIME);
        return session;
    }

    private ConversationSession deliveryWaitingForCash() {
        ConversationSession session = deliveryWaitingForPayment();
        stateMachine.selectCash(session, TRANSITION_TIME);
        return session;
    }

    private ConversationSession deliveryWaitingForTransfer() {
        ConversationSession session = deliveryWaitingForPayment();
        stateMachine.selectTransfer(session, TRANSITION_TIME);
        return session;
    }

    private ConversationSession pickupWaitingForName() {
        ConversationSession session = waitingForCartConfirmation();
        stateMachine.confirmCart(session, TRANSITION_TIME);
        stateMachine.selectPickup(session, TRANSITION_TIME);
        return session;
    }

    private ConversationSession pickupWaitingForPayment() {
        ConversationSession session = pickupWaitingForName();
        stateMachine.providePickupName(session, "Li", TRANSITION_TIME);
        return session;
    }

    private ConversationSession confirmedSession() {
        ConversationSession session = deliveryWaitingForCash();
        stateMachine.provideCashDenomination(session, new BigDecimal("100.00"), TRANSITION_TIME);
        stateMachine.confirmCheckout(session, TRANSITION_TIME);
        return session;
    }

    private void assertInvalidAndUnchanged(ConversationSession session,
                                           Consumer<ConversationSession> command,
                                           ConversationTransitionAction action) {
        Snapshot before = Snapshot.from(session);

        assertThatThrownBy(() -> command.accept(session))
                .isInstanceOfSatisfying(InvalidConversationTransitionException.class, exception -> {
                    assertThat(exception.getCurrentState()).isEqualTo(before.state());
                    assertThat(exception.getAttemptedAction()).isEqualTo(action);
                    assertThat(exception.getAllowedSourceStates()).isNotEmpty();
                });

        assertThat(Snapshot.from(session)).isEqualTo(before);
    }

    private void assertReset(ConversationSession session) {
        assertThat(session.getState()).isEqualTo(ConversationState.ORDERING);
        assertThat(session.getFulfillmentType()).isNull();
        assertThat(session.getDeliveryAddress()).isNull();
        assertThat(session.getPickupName()).isNull();
        assertThat(session.getPaymentMethod()).isNull();
        assertThat(session.getCashDenomination()).isNull();
        assertThat(session.getTransferReceiptPath()).isNull();
        assertThat(session.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    private void forceField(ConversationSession session, String fieldName, Object value) {
        try {
            Field field = ConversationSession.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(session, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Test fixture could not create malformed persisted state", exception);
        }
    }

    private record InvalidAction(SessionFactory sessionFactory, Consumer<ConversationSession> command) {
    }

    @FunctionalInterface
    private interface SessionFactory {
        ConversationSession get();
    }

    private record Snapshot(ConversationState state,
                            FulfillmentType fulfillmentType,
                            String deliveryAddress,
                            String pickupName,
                            PaymentMethod paymentMethod,
                            BigDecimal cashDenomination,
                            String transferReceiptPath,
                            Instant createdAt,
                            Instant updatedAt,
                            Instant lastActivityAt) {

        private static Snapshot from(ConversationSession session) {
            return new Snapshot(
                    session.getState(),
                    session.getFulfillmentType(),
                    session.getDeliveryAddress(),
                    session.getPickupName(),
                    session.getPaymentMethod(),
                    session.getCashDenomination(),
                    session.getTransferReceiptPath(),
                    session.getCreatedAt(),
                    session.getUpdatedAt(),
                    session.getLastActivityAt());
        }
    }


    @Test
    void classifiesTransitionRejectionReasonsWithoutMutatingTheSession() {
        assertInvalidReasonAndUnchanged(newSession(),
                session -> stateMachine.provideDeliveryAddress(session, "Calle", TRANSITION_TIME),
                InvalidConversationTransitionReason.INVALID_SOURCE_STATE);
        assertInvalidReasonAndUnchanged(newSession(),
                session -> stateMachine.confirmCheckout(session, TRANSITION_TIME),
                InvalidConversationTransitionReason.INVALID_SOURCE_STATE);

        ConversationSession cancelled = newSession();
        stateMachine.cancelCheckout(cancelled, TRANSITION_TIME);
        assertInvalidReasonAndUnchanged(cancelled,
                session -> stateMachine.requestCheckoutReview(session, TRANSITION_TIME),
                InvalidConversationTransitionReason.INVALID_SOURCE_STATE);
        assertInvalidReasonAndUnchanged(confirmedSession(),
                session -> stateMachine.selectDelivery(session, TRANSITION_TIME),
                InvalidConversationTransitionReason.INVALID_SOURCE_STATE);

        assertInvalidReasonAndUnchanged(deliveryWaitingForAddress(),
                session -> stateMachine.provideDeliveryAddress(session, " ", TRANSITION_TIME),
                InvalidConversationTransitionReason.INVALID_INPUT);
        assertInvalidReasonAndUnchanged(pickupWaitingForName(),
                session -> stateMachine.providePickupName(session, "A", TRANSITION_TIME),
                InvalidConversationTransitionReason.INVALID_INPUT);
        assertInvalidReasonAndUnchanged(deliveryWaitingForCash(),
                session -> stateMachine.provideCashDenomination(session, BigDecimal.ZERO, TRANSITION_TIME),
                InvalidConversationTransitionReason.INVALID_INPUT);
        assertInvalidReasonAndUnchanged(deliveryWaitingForCash(),
                session -> stateMachine.provideCashDenomination(session, new BigDecimal("1.001"), TRANSITION_TIME),
                InvalidConversationTransitionReason.INVALID_INPUT);
        assertInvalidReasonAndUnchanged(deliveryWaitingForTransfer(),
                session -> stateMachine.provideTransferReceipt(session, " ", TRANSITION_TIME),
                InvalidConversationTransitionReason.INVALID_INPUT);
        assertInvalidReasonAndUnchanged(deliveryWaitingForAddress(),
                session -> stateMachine.provideDeliveryAddress(session, "x".repeat(501), TRANSITION_TIME),
                InvalidConversationTransitionReason.INVALID_INPUT);
        assertInvalidReasonAndUnchanged(pickupWaitingForName(),
                session -> stateMachine.providePickupName(session, "x".repeat(121), TRANSITION_TIME),
                InvalidConversationTransitionReason.INVALID_INPUT);
        assertInvalidReasonAndUnchanged(deliveryWaitingForCash(),
                session -> stateMachine.provideCashDenomination(session, new BigDecimal("999999999999999999.99"),
                        TRANSITION_TIME),
                InvalidConversationTransitionReason.INVALID_INPUT);
        assertInvalidReasonAndUnchanged(deliveryWaitingForTransfer(),
                session -> stateMachine.provideTransferReceipt(session, "x".repeat(1025), TRANSITION_TIME),
                InvalidConversationTransitionReason.INVALID_INPUT);

        ConversationSession missingFulfillment = deliveryWaitingForAddress();
        forceField(missingFulfillment, "fulfillmentType", null);
        assertInvalidReasonAndUnchanged(missingFulfillment,
                session -> stateMachine.provideDeliveryAddress(session, "Calle", TRANSITION_TIME),
                InvalidConversationTransitionReason.INVARIANT_VIOLATION);

        ConversationSession deliveryWithInvalidAddress = deliveryWaitingForPayment();
        forceField(deliveryWithInvalidAddress, "deliveryAddress", " ");
        assertInvalidReasonAndUnchanged(deliveryWithInvalidAddress,
                session -> stateMachine.selectCash(session, TRANSITION_TIME),
                InvalidConversationTransitionReason.INVARIANT_VIOLATION);

        ConversationSession pickupWithInvalidName = pickupWaitingForPayment();
        forceField(pickupWithInvalidName, "pickupName", " ");
        assertInvalidReasonAndUnchanged(pickupWithInvalidName,
                session -> stateMachine.selectCash(session, TRANSITION_TIME),
                InvalidConversationTransitionReason.INVARIANT_VIOLATION);

        ConversationSession malformedReadyPayment = deliveryWaitingForCash();
        stateMachine.provideCashDenomination(malformedReadyPayment, new BigDecimal("50.00"), TRANSITION_TIME);
        forceField(malformedReadyPayment, "paymentMethod", null);
        assertInvalidReasonAndUnchanged(malformedReadyPayment,
                session -> stateMachine.confirmCheckout(session, TRANSITION_TIME),
                InvalidConversationTransitionReason.INVARIANT_VIOLATION);

        ConversationSession malformedReadyCard = pickupWaitingForPayment();
        stateMachine.selectCard(malformedReadyCard, TRANSITION_TIME);
        forceField(malformedReadyCard, "fulfillmentType", FulfillmentType.DELIVERY);
        forceField(malformedReadyCard, "deliveryAddress", "Calle 123");
        assertInvalidReasonAndUnchanged(malformedReadyCard,
                session -> stateMachine.confirmCheckout(session, TRANSITION_TIME),
                InvalidConversationTransitionReason.INVARIANT_VIOLATION);

        assertInvalidReasonAndUnchanged(deliveryWaitingForPayment(),
                session -> stateMachine.selectCard(session, TRANSITION_TIME),
                InvalidConversationTransitionReason.UNSUPPORTED_OPTION);
    }

    private void assertInvalidReasonAndUnchanged(ConversationSession session,
                                                 Consumer<ConversationSession> command,
                                                 InvalidConversationTransitionReason expectedReason) {
        Snapshot before = Snapshot.from(session);

        assertThatThrownBy(() -> command.accept(session))
                .isInstanceOfSatisfying(InvalidConversationTransitionException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo(expectedReason));

        assertThat(Snapshot.from(session)).isEqualTo(before);
    }
}
