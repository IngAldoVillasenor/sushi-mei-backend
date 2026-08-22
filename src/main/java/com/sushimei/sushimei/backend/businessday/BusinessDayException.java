package com.sushimei.sushimei.backend.businessday;

public class BusinessDayException extends RuntimeException {

    private final BusinessDayError error;

    public BusinessDayException(BusinessDayError error) {
        this.error = error;
    }

    public BusinessDayException(BusinessDayError error, Throwable cause) {
        super(cause);
        this.error = error;
    }

    public BusinessDayError getError() {
        return error;
    }
}
