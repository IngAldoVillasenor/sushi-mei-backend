package com.sushimei.sushimei.backend.checkout;

public class ActiveCartNotFoundException extends RuntimeException {

    public ActiveCartNotFoundException() {
        super("No active cart is available for deterministic checkout.");
    }
}
