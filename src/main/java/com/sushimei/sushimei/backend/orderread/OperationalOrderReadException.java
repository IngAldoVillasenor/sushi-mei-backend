package com.sushimei.sushimei.backend.orderread;

/** Safe query-boundary failure for a missing operational order. */
public class OperationalOrderReadException extends RuntimeException {

    public OperationalOrderReadException() {
        super("Operational order was not found");
    }
}
