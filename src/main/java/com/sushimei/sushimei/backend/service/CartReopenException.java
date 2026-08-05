package com.sushimei.sushimei.backend.service;

import java.util.Objects;

/**
 * Rejects an unsafe cart merge without exposing dish, customer, or money
 * details in its message.
 */
public class CartReopenException extends RuntimeException {

    private final CartReopenFailureReason reason;

    public CartReopenException(CartReopenFailureReason reason) {
        super("Cart reopening cannot be completed: "
                + Objects.requireNonNull(reason, "reason must not be null") + ".");
        this.reason = reason;
    }

    public CartReopenFailureReason getReason() {
        return reason;
    }
}
