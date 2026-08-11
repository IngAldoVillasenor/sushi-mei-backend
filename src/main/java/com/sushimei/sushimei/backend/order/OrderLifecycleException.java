package com.sushimei.sushimei.backend.order;

import java.util.Objects;

public class OrderLifecycleException extends RuntimeException {

    private final OrderLifecycleError error;

    public OrderLifecycleException(OrderLifecycleError error) {
        super(Objects.requireNonNull(error, "error must not be null").name());
        this.error = error;
    }

    public OrderLifecycleError getError() {
        return error;
    }
}
