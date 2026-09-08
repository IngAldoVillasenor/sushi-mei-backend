package com.cardovia.merkon.backend.conversation;

public enum PaymentMethod {
    CASH,
    TRANSFER,
    CARD;

    public boolean requiresCashDenomination() {
        return this == CASH;
    }

    public boolean requiresTransferReceipt() {
        return this == TRANSFER;
    }
}
