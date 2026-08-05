package com.sushimei.sushimei.backend.conversation;

import java.util.Objects;

public record CheckoutIntentResult(ConversationTransitionAction action,
                                   ConversationState resultingState,
                                   FulfillmentType fulfillmentType,
                                   PaymentMethod paymentMethod) {

    public CheckoutIntentResult {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(resultingState, "resultingState must not be null");
    }

    static CheckoutIntentResult from(ConversationTransitionAction action, ConversationSession session) {
        ConversationSession resultingSession = Objects.requireNonNull(session, "session must not be null");
        return new CheckoutIntentResult(
                action,
                resultingSession.getState(),
                resultingSession.getFulfillmentType(),
                resultingSession.getPaymentMethod());
    }
}
