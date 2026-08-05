package com.sushimei.sushimei.backend.checkout;

public class EmptyCartException extends RuntimeException {

    public EmptyCartException() {
        super("Deterministic checkout cannot use an empty cart.");
    }
}
