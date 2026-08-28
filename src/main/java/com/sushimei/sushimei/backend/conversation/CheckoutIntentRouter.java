package com.sushimei.sushimei.backend.conversation;

import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Objects;

@Service
@ConditionalOnProperty(prefix = "sushimei.features.whatsapp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CheckoutIntentRouter {

    private final ConversationTransitionService conversationTransitionService;

    public CheckoutIntentRouter(ConversationTransitionService conversationTransitionService) {
        this.conversationTransitionService = Objects.requireNonNull(
                conversationTransitionService, "conversationTransitionService must not be null");
    }

    public CheckoutIntentResult route(String phoneNumber, CheckoutIntent intent) {
        Objects.requireNonNull(intent, "intent must not be null");

        if (intent instanceof CheckoutIntent.RequestCheckoutReview) {
            return result(ConversationTransitionAction.REQUEST_CHECKOUT_REVIEW,
                    conversationTransitionService.requestCheckoutReview(phoneNumber));
        }
        if (intent instanceof CheckoutIntent.ConfirmCart) {
            return result(ConversationTransitionAction.CONFIRM_CART,
                    conversationTransitionService.confirmCart(phoneNumber));
        }
        if (intent instanceof CheckoutIntent.ContinueOrdering) {
            return result(ConversationTransitionAction.CONTINUE_ORDERING,
                    conversationTransitionService.continueOrdering(phoneNumber));
        }
        if (intent instanceof CheckoutIntent.SelectFulfillment selectFulfillment) {
            return routeFulfillment(phoneNumber, selectFulfillment.fulfillmentType());
        }
        if (intent instanceof CheckoutIntent.ProvideDeliveryAddress provideDeliveryAddress) {
            return result(ConversationTransitionAction.PROVIDE_DELIVERY_ADDRESS,
                    conversationTransitionService.provideDeliveryAddress(phoneNumber, provideDeliveryAddress.address()));
        }
        if (intent instanceof CheckoutIntent.ProvidePickupName providePickupName) {
            return result(ConversationTransitionAction.PROVIDE_PICKUP_NAME,
                    conversationTransitionService.providePickupName(phoneNumber, providePickupName.pickupName()));
        }
        if (intent instanceof CheckoutIntent.SelectPaymentMethod selectPaymentMethod) {
            return routePaymentMethod(phoneNumber, selectPaymentMethod.paymentMethod());
        }
        if (intent instanceof CheckoutIntent.ProvideCashDenomination provideCashDenomination) {
            return result(ConversationTransitionAction.PROVIDE_CASH_DENOMINATION,
                    conversationTransitionService.provideCashDenomination(phoneNumber,
                            provideCashDenomination.denomination()));
        }
        if (intent instanceof CheckoutIntent.ProvideTransferReceipt provideTransferReceipt) {
            return result(ConversationTransitionAction.PROVIDE_TRANSFER_RECEIPT,
                    conversationTransitionService.provideTransferReceipt(phoneNumber, provideTransferReceipt.receiptPath()));
        }
        if (intent instanceof CheckoutIntent.ConfirmCheckout) {
            return result(ConversationTransitionAction.CONFIRM_CHECKOUT,
                    conversationTransitionService.confirmCheckout(phoneNumber));
        }
        if (intent instanceof CheckoutIntent.CancelCheckout) {
            return result(ConversationTransitionAction.CANCEL_CHECKOUT,
                    conversationTransitionService.cancelCheckout(phoneNumber));
        }

        throw new IllegalArgumentException("Unsupported checkout intent type");
    }

    private CheckoutIntentResult routeFulfillment(String phoneNumber, FulfillmentType fulfillmentType) {
        FulfillmentType selectedFulfillmentType = Objects.requireNonNull(
                fulfillmentType, "fulfillmentType must not be null");
        return switch (selectedFulfillmentType) {
            case DELIVERY -> result(ConversationTransitionAction.SELECT_DELIVERY,
                    conversationTransitionService.selectDelivery(phoneNumber));
            case PICKUP -> result(ConversationTransitionAction.SELECT_PICKUP,
                    conversationTransitionService.selectPickup(phoneNumber));
        };
    }

    private CheckoutIntentResult routePaymentMethod(String phoneNumber, PaymentMethod paymentMethod) {
        PaymentMethod selectedPaymentMethod = Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
        return switch (selectedPaymentMethod) {
            case CASH -> result(ConversationTransitionAction.SELECT_CASH,
                    conversationTransitionService.selectCash(phoneNumber));
            case TRANSFER -> result(ConversationTransitionAction.SELECT_TRANSFER,
                    conversationTransitionService.selectTransfer(phoneNumber));
            case CARD -> result(ConversationTransitionAction.SELECT_CARD,
                    conversationTransitionService.selectCard(phoneNumber));
        };
    }

    private CheckoutIntentResult result(ConversationTransitionAction action, ConversationSession session) {
        return CheckoutIntentResult.from(action, session);
    }
}
