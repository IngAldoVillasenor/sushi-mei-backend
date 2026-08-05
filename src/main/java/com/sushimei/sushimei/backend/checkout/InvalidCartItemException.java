package com.sushimei.sushimei.backend.checkout;

import java.util.Objects;

public class InvalidCartItemException extends RuntimeException {

    private final InvalidCartItemReason reason;

    public InvalidCartItemException(InvalidCartItemReason reason) {
        super("Cart data is invalid for deterministic checkout: "
                + Objects.requireNonNull(reason, "reason must not be null"));
        this.reason = reason;
    }

    public InvalidCartItemReason getReason() {
        return reason;
    }
}
