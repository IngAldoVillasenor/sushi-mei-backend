package com.sushimei.sushimei.backend.checkout;

public class MultipleActiveCartsException extends RuntimeException {

    public MultipleActiveCartsException() {
        super("Deterministic checkout requires exactly one active cart.");
    }
}
