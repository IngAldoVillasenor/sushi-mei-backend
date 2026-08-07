package com.sushimei.sushimei.backend.checkout;

import java.util.Objects;

/** Safe result for a future trusted adapter; it never exposes mutable order data. */
public record CheckoutCompletionResult(CheckoutCompletionOutcome outcome, Long orderId) {

    public CheckoutCompletionResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
    }
}
