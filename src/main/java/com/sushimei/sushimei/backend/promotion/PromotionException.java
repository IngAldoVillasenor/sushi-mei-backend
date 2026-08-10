package com.sushimei.sushimei.backend.promotion;

import java.util.Objects;

public class PromotionException extends RuntimeException {

    private final PromotionError error;

    public PromotionException(PromotionError error) {
        super(Objects.requireNonNull(error, "error must not be null").name());
        this.error = error;
    }

    public PromotionError getError() {
        return error;
    }
}
