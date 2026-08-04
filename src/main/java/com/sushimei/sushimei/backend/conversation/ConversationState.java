package com.sushimei.sushimei.backend.conversation;

/**
 * Persistence definitions for a future deterministic checkout lifecycle.
 * Phase 1 does not derive or transition this state from inbound messages.
 */
public enum ConversationState {
    ORDERING,
    WAITING_CART_CONFIRMATION,
    WAITING_FULFILLMENT_TYPE,
    WAITING_DELIVERY_ADDRESS,
    WAITING_PICKUP_NAME,
    WAITING_PAYMENT_METHOD,
    WAITING_CASH_DENOMINATION,
    WAITING_TRANSFER_RECEIPT,
    READY_TO_CONFIRM,
    ORDER_CONFIRMED,
    CANCELLED
}
