package com.sushimei.sushimei.backend.orderread;

public class InvalidDateRangeException extends IllegalArgumentException {
    public InvalidDateRangeException(String message) {
        super(message);
    }
}
