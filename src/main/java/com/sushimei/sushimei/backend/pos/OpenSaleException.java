package com.sushimei.sushimei.backend.pos;

import java.util.Objects;

public final class OpenSaleException extends RuntimeException {
    private final OpenSaleError error;

    public OpenSaleException(OpenSaleError error) {
        this.error = Objects.requireNonNull(error, "error must not be null");
    }

    public OpenSaleException(OpenSaleError error, Throwable cause) {
        super(cause);
        this.error = Objects.requireNonNull(error, "error must not be null");
    }

    public OpenSaleError getError() { return error; }
}
