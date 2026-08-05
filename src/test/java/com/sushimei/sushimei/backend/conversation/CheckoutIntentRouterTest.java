package com.sushimei.sushimei.backend.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckoutIntentRouterTest {

    private static final String PHONE_NUMBER = " 525512345678 ";

    @Mock
    private ConversationTransitionService conversationTransitionService;

    private CheckoutIntentRouter router;

    @BeforeEach
    void setUp() {
        router = new CheckoutIntentRouter(conversationTransitionService);
    }

    @Test
    void routesRequestCheckoutReview() {
        ConversationSession session = transitionResult(ConversationState.WAITING_CART_CONFIRMATION, null, null);
        when(conversationTransitionService.requestCheckoutReview(PHONE_NUMBER)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER, new CheckoutIntent.RequestCheckoutReview());

        assertResult(result, ConversationTransitionAction.REQUEST_CHECKOUT_REVIEW,
                ConversationState.WAITING_CART_CONFIRMATION, null, null);
        verify(conversationTransitionService).requestCheckoutReview(PHONE_NUMBER);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void routesConfirmCart() {
        ConversationSession session = transitionResult(ConversationState.WAITING_FULFILLMENT_TYPE, null, null);
        when(conversationTransitionService.confirmCart(PHONE_NUMBER)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER, new CheckoutIntent.ConfirmCart());

        assertResult(result, ConversationTransitionAction.CONFIRM_CART,
                ConversationState.WAITING_FULFILLMENT_TYPE, null, null);
        verify(conversationTransitionService).confirmCart(PHONE_NUMBER);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void routesContinueOrdering() {
        ConversationSession session = transitionResult(ConversationState.ORDERING, null, null);
        when(conversationTransitionService.continueOrdering(PHONE_NUMBER)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER, new CheckoutIntent.ContinueOrdering());

        assertResult(result, ConversationTransitionAction.CONTINUE_ORDERING, ConversationState.ORDERING, null, null);
        verify(conversationTransitionService).continueOrdering(PHONE_NUMBER);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void routesDeliveryFulfillment() {
        ConversationSession session = transitionResult(ConversationState.WAITING_DELIVERY_ADDRESS,
                FulfillmentType.DELIVERY, null);
        when(conversationTransitionService.selectDelivery(PHONE_NUMBER)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER,
                new CheckoutIntent.SelectFulfillment(FulfillmentType.DELIVERY));

        assertResult(result, ConversationTransitionAction.SELECT_DELIVERY,
                ConversationState.WAITING_DELIVERY_ADDRESS, FulfillmentType.DELIVERY, null);
        verify(conversationTransitionService).selectDelivery(PHONE_NUMBER);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void routesPickupFulfillment() {
        ConversationSession session = transitionResult(ConversationState.WAITING_PICKUP_NAME,
                FulfillmentType.PICKUP, null);
        when(conversationTransitionService.selectPickup(PHONE_NUMBER)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER,
                new CheckoutIntent.SelectFulfillment(FulfillmentType.PICKUP));

        assertResult(result, ConversationTransitionAction.SELECT_PICKUP,
                ConversationState.WAITING_PICKUP_NAME, FulfillmentType.PICKUP, null);
        verify(conversationTransitionService).selectPickup(PHONE_NUMBER);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void routesDeliveryAddressWithoutRewritingIt() {
        String address = "  Calle 123  ";
        ConversationSession session = transitionResult(ConversationState.WAITING_PAYMENT_METHOD,
                FulfillmentType.DELIVERY, null);
        when(conversationTransitionService.provideDeliveryAddress(PHONE_NUMBER, address)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER, new CheckoutIntent.ProvideDeliveryAddress(address));

        assertResult(result, ConversationTransitionAction.PROVIDE_DELIVERY_ADDRESS,
                ConversationState.WAITING_PAYMENT_METHOD, FulfillmentType.DELIVERY, null);
        verify(conversationTransitionService).provideDeliveryAddress(PHONE_NUMBER, address);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void routesPickupNameWithoutRewritingIt() {
        String pickupName = "  Li  ";
        ConversationSession session = transitionResult(ConversationState.WAITING_PAYMENT_METHOD,
                FulfillmentType.PICKUP, null);
        when(conversationTransitionService.providePickupName(PHONE_NUMBER, pickupName)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER, new CheckoutIntent.ProvidePickupName(pickupName));

        assertResult(result, ConversationTransitionAction.PROVIDE_PICKUP_NAME,
                ConversationState.WAITING_PAYMENT_METHOD, FulfillmentType.PICKUP, null);
        verify(conversationTransitionService).providePickupName(PHONE_NUMBER, pickupName);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void routesCashPaymentMethod() {
        ConversationSession session = transitionResult(ConversationState.WAITING_CASH_DENOMINATION,
                FulfillmentType.PICKUP, PaymentMethod.CASH);
        when(conversationTransitionService.selectCash(PHONE_NUMBER)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER,
                new CheckoutIntent.SelectPaymentMethod(PaymentMethod.CASH));

        assertResult(result, ConversationTransitionAction.SELECT_CASH,
                ConversationState.WAITING_CASH_DENOMINATION, FulfillmentType.PICKUP, PaymentMethod.CASH);
        verify(conversationTransitionService).selectCash(PHONE_NUMBER);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void routesTransferPaymentMethod() {
        ConversationSession session = transitionResult(ConversationState.WAITING_TRANSFER_RECEIPT,
                FulfillmentType.DELIVERY, PaymentMethod.TRANSFER);
        when(conversationTransitionService.selectTransfer(PHONE_NUMBER)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER,
                new CheckoutIntent.SelectPaymentMethod(PaymentMethod.TRANSFER));

        assertResult(result, ConversationTransitionAction.SELECT_TRANSFER,
                ConversationState.WAITING_TRANSFER_RECEIPT, FulfillmentType.DELIVERY, PaymentMethod.TRANSFER);
        verify(conversationTransitionService).selectTransfer(PHONE_NUMBER);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void routesCardPaymentMethod() {
        ConversationSession session = transitionResult(ConversationState.READY_TO_CONFIRM,
                FulfillmentType.PICKUP, PaymentMethod.CARD);
        when(conversationTransitionService.selectCard(PHONE_NUMBER)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER,
                new CheckoutIntent.SelectPaymentMethod(PaymentMethod.CARD));

        assertResult(result, ConversationTransitionAction.SELECT_CARD,
                ConversationState.READY_TO_CONFIRM, FulfillmentType.PICKUP, PaymentMethod.CARD);
        verify(conversationTransitionService).selectCard(PHONE_NUMBER);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void routesCashDenominationWithoutRewritingIt() {
        BigDecimal denomination = new BigDecimal("500.000");
        ConversationSession session = transitionResult(ConversationState.READY_TO_CONFIRM,
                FulfillmentType.DELIVERY, PaymentMethod.CASH);
        when(conversationTransitionService.provideCashDenomination(PHONE_NUMBER, denomination)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER,
                new CheckoutIntent.ProvideCashDenomination(denomination));

        assertResult(result, ConversationTransitionAction.PROVIDE_CASH_DENOMINATION,
                ConversationState.READY_TO_CONFIRM, FulfillmentType.DELIVERY, PaymentMethod.CASH);
        verify(conversationTransitionService).provideCashDenomination(PHONE_NUMBER, denomination);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void routesTransferReceiptWithoutRewritingIt() {
        String receiptPath = "  receipts/transfer.jpg  ";
        ConversationSession session = transitionResult(ConversationState.READY_TO_CONFIRM,
                FulfillmentType.PICKUP, PaymentMethod.TRANSFER);
        when(conversationTransitionService.provideTransferReceipt(PHONE_NUMBER, receiptPath)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER,
                new CheckoutIntent.ProvideTransferReceipt(receiptPath));

        assertResult(result, ConversationTransitionAction.PROVIDE_TRANSFER_RECEIPT,
                ConversationState.READY_TO_CONFIRM, FulfillmentType.PICKUP, PaymentMethod.TRANSFER);
        verify(conversationTransitionService).provideTransferReceipt(PHONE_NUMBER, receiptPath);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void routesConfirmCheckout() {
        ConversationSession session = transitionResult(ConversationState.ORDER_CONFIRMED,
                FulfillmentType.PICKUP, PaymentMethod.CARD);
        when(conversationTransitionService.confirmCheckout(PHONE_NUMBER)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER, new CheckoutIntent.ConfirmCheckout());

        assertResult(result, ConversationTransitionAction.CONFIRM_CHECKOUT,
                ConversationState.ORDER_CONFIRMED, FulfillmentType.PICKUP, PaymentMethod.CARD);
        verify(conversationTransitionService).confirmCheckout(PHONE_NUMBER);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void routesCancelCheckout() {
        ConversationSession session = transitionResult(ConversationState.CANCELLED,
                FulfillmentType.DELIVERY, PaymentMethod.CASH);
        when(conversationTransitionService.cancelCheckout(PHONE_NUMBER)).thenReturn(session);

        CheckoutIntentResult result = router.route(PHONE_NUMBER, new CheckoutIntent.CancelCheckout());

        assertResult(result, ConversationTransitionAction.CANCEL_CHECKOUT,
                ConversationState.CANCELLED, FulfillmentType.DELIVERY, PaymentMethod.CASH);
        verify(conversationTransitionService).cancelCheckout(PHONE_NUMBER);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void rejectsNullStructuralIntentsBeforeServiceInteraction() {
        assertThatNullPointerException().isThrownBy(() -> router.route(PHONE_NUMBER, null));
        assertThatNullPointerException().isThrownBy(() -> router.route(PHONE_NUMBER,
                new CheckoutIntent.SelectFulfillment(null)));
        assertThatNullPointerException().isThrownBy(() -> router.route(PHONE_NUMBER,
                new CheckoutIntent.SelectPaymentMethod(null)));

        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void forwardsNullableBusinessValuesToTheTransitionService() {
        ConversationSession session = transitionResult(ConversationState.WAITING_DELIVERY_ADDRESS, null, null);
        when(conversationTransitionService.provideDeliveryAddress(PHONE_NUMBER, null)).thenReturn(session);
        router.route(PHONE_NUMBER, new CheckoutIntent.ProvideDeliveryAddress(null));
        verify(conversationTransitionService).provideDeliveryAddress(PHONE_NUMBER, null);
        verifyNoMoreInteractions(conversationTransitionService);

        resetTransitionService();
        when(conversationTransitionService.providePickupName(PHONE_NUMBER, null)).thenReturn(session);
        router.route(PHONE_NUMBER, new CheckoutIntent.ProvidePickupName(null));
        verify(conversationTransitionService).providePickupName(PHONE_NUMBER, null);
        verifyNoMoreInteractions(conversationTransitionService);

        resetTransitionService();
        when(conversationTransitionService.provideCashDenomination(PHONE_NUMBER, null)).thenReturn(session);
        router.route(PHONE_NUMBER, new CheckoutIntent.ProvideCashDenomination(null));
        verify(conversationTransitionService).provideCashDenomination(PHONE_NUMBER, null);
        verifyNoMoreInteractions(conversationTransitionService);

        resetTransitionService();
        when(conversationTransitionService.provideTransferReceipt(PHONE_NUMBER, null)).thenReturn(session);
        router.route(PHONE_NUMBER, new CheckoutIntent.ProvideTransferReceipt(null));
        verify(conversationTransitionService).provideTransferReceipt(PHONE_NUMBER, null);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void propagatesDomainAndPersistenceExceptionsUnchanged() {
        InvalidConversationTransitionException invalidTransition = new InvalidConversationTransitionException(
                ConversationState.ORDERING,
                ConversationTransitionAction.CONFIRM_CHECKOUT,
                EnumSet.of(ConversationState.READY_TO_CONFIRM),
                InvalidConversationTransitionReason.INVALID_SOURCE_STATE);
        when(conversationTransitionService.confirmCheckout(PHONE_NUMBER)).thenThrow(invalidTransition);

        assertThatThrownBy(() -> router.route(PHONE_NUMBER, new CheckoutIntent.ConfirmCheckout()))
                .isSameAs(invalidTransition);
        verify(conversationTransitionService).confirmCheckout(PHONE_NUMBER);
        verifyNoMoreInteractions(conversationTransitionService);

        resetTransitionService();
        ConversationSessionNotFoundException sessionNotFound = new ConversationSessionNotFoundException(
                ConversationTransitionAction.CONFIRM_CHECKOUT);
        when(conversationTransitionService.confirmCheckout(PHONE_NUMBER)).thenThrow(sessionNotFound);

        assertThatThrownBy(() -> router.route(PHONE_NUMBER, new CheckoutIntent.ConfirmCheckout()))
                .isSameAs(sessionNotFound);
        verify(conversationTransitionService).confirmCheckout(PHONE_NUMBER);
        verifyNoMoreInteractions(conversationTransitionService);

        resetTransitionService();
        ObjectOptimisticLockingFailureException optimisticLockFailure =
                new ObjectOptimisticLockingFailureException(ConversationSession.class, "session");
        when(conversationTransitionService.confirmCheckout(PHONE_NUMBER)).thenThrow(optimisticLockFailure);

        assertThatThrownBy(() -> router.route(PHONE_NUMBER, new CheckoutIntent.ConfirmCheckout()))
                .isSameAs(optimisticLockFailure);
        verify(conversationTransitionService).confirmCheckout(PHONE_NUMBER);
        verifyNoMoreInteractions(conversationTransitionService);
    }

    @Test
    void exposesOnlyTheSafeResultFieldsAndHasOnlyTheTransitionServiceDependency() {
        assertThat(Arrays.stream(CheckoutIntentResult.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("action", "resultingState", "fulfillmentType", "paymentMethod");

        Constructor<?>[] constructors = CheckoutIntentRouter.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(ConversationTransitionService.class);
        assertThat(Arrays.stream(CheckoutIntentRouter.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .containsExactly(ConversationTransitionService.class.getName());
        assertThat(CheckoutIntentRouter.class.getAnnotation(Service.class)).isNotNull();
    }

    private ConversationSession transitionResult(ConversationState state,
                                                 FulfillmentType fulfillmentType,
                                                 PaymentMethod paymentMethod) {
        ConversationSession session = mock(ConversationSession.class);
        when(session.getState()).thenReturn(state);
        when(session.getFulfillmentType()).thenReturn(fulfillmentType);
        when(session.getPaymentMethod()).thenReturn(paymentMethod);
        return session;
    }

    private void assertResult(CheckoutIntentResult result,
                              ConversationTransitionAction action,
                              ConversationState state,
                              FulfillmentType fulfillmentType,
                              PaymentMethod paymentMethod) {
        assertThat(result.action()).isEqualTo(action);
        assertThat(result.resultingState()).isEqualTo(state);
        assertThat(result.fulfillmentType()).isEqualTo(fulfillmentType);
        assertThat(result.paymentMethod()).isEqualTo(paymentMethod);
    }

    private void resetTransitionService() {
        org.mockito.Mockito.reset(conversationTransitionService);
    }
}
