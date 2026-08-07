package com.sushimei.sushimei.backend.checkout;

import java.util.Objects;

/** Non-sensitive deterministic checkout failure. */
public class CheckoutCompletionException extends RuntimeException {

    private final CheckoutCompletionFailureReason reason;

    public CheckoutCompletionException(CheckoutCompletionFailureReason reason) {
        super(Objects.requireNonNull(reason, "reason must not be null").name());
        this.reason = reason;
    }

    public CheckoutCompletionFailureReason getReason() {
        return reason;
    }
}
