package com.cardovia.merkon.backend.security;

import java.util.Objects;

/** Provider-neutral rendered transactional message. It is never persisted. */
public record TransactionalEmail(
        String recipient,
        String subject,
        String htmlBody,
        String textBody,
        String idempotencyKey) {

    public TransactionalEmail {
        recipient = Objects.requireNonNull(recipient);
        subject = Objects.requireNonNull(subject);
        htmlBody = Objects.requireNonNull(htmlBody);
        textBody = Objects.requireNonNull(textBody);
        idempotencyKey = Objects.requireNonNull(idempotencyKey);
    }
}
