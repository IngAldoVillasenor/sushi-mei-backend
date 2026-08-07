package com.sushimei.sushimei.backend.checkout;

public enum CheckoutCompletionFailureReason {
    INVALID_COMMAND,
    CART_NOT_FOUND,
    CART_PHONE_MISMATCH,
    CART_NOT_OPEN,
    CONVERSATION_SESSION_NOT_FOUND,
    IDEMPOTENCY_PHONE_MISMATCH,
    IDEMPOTENCY_INCOMPATIBLE_ORDER
}
