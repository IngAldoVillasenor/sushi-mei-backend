package com.sushimei.sushimei.backend.checkout;

import java.util.Objects;

/**
 * Signals corrupted or incompatible persisted monetary representations without
 * exposing values or customer data in its message.
 */
public class MonetaryCompatibilityException extends RuntimeException {

    private final MonetaryCompatibilityReason reason;

    public MonetaryCompatibilityException(MonetaryCompatibilityReason reason) {
        super("Monetary representations are incompatible: " + Objects.requireNonNull(reason, "reason must not be null") + ".");
        this.reason = reason;
    }

    public MonetaryCompatibilityException(MonetaryCompatibilityReason reason, Throwable cause) {
        super("Monetary representations are incompatible: " + Objects.requireNonNull(reason, "reason must not be null") + ".", cause);
        this.reason = reason;
    }

    public MonetaryCompatibilityReason getReason() {
        return reason;
    }
}
