package com.cardovia.merkon.backend.security;

/** Safe, classified delivery failure. It intentionally carries no provider payload or secret. */
class TransactionalEmailDeliveryException extends RuntimeException {

    private final String reasonCode;

    TransactionalEmailDeliveryException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    TransactionalEmailDeliveryException(String reasonCode, Throwable cause) {
        super(reasonCode, cause);
        this.reasonCode = reasonCode;
    }

    String reasonCode() {
        return reasonCode;
    }
}
