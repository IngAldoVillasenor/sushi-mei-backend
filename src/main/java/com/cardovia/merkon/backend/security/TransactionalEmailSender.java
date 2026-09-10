package com.cardovia.merkon.backend.security;

/** Application-owned boundary; account lifecycle code has no provider dependency. */
public interface TransactionalEmailSender {

    void send(TransactionalEmail email);
}
