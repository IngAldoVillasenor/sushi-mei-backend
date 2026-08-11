package com.sushimei.sushimei.backend.pos;

public class ManualPosOrderException extends RuntimeException {
    private final ManualPosOrderError error;

    public ManualPosOrderException(ManualPosOrderError error) {
        this.error = error;
    }

    public ManualPosOrderException(ManualPosOrderError error, Throwable cause) {
        super(cause);
        this.error = error;
    }

    public ManualPosOrderError getError() {
        return error;
    }
}
