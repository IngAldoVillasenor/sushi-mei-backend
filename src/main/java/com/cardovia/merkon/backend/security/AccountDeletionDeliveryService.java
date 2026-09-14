package com.cardovia.merkon.backend.security;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Performs provider I/O only after a deletion request is durable. */
@Service
class AccountDeletionDeliveryService {

    private final TransactionalEmailSender sender;
    private final AccountDeletionEmailTemplate template;
    private final SecurityAuditService audit;

    AccountDeletionDeliveryService(TransactionalEmailSender sender,
                                   AccountDeletionEmailTemplate template,
                                   SecurityAuditService audit) {
        this.sender = sender;
        this.template = template;
        this.audit = audit;
    }

    void deliver(Long userId,
                 String recipient,
                 UUID requestId,
                 Instant expiresAt,
                 String plaintextToken) {
        try {
            sender.send(template.render(recipient, plaintextToken, expiresAt, requestId));
            audit.record(
                    SecurityAuditEventType.ACCOUNT_DELETION_SEND_SUCCEEDED,
                    null,
                    userId,
                    null,
                    null,
                    null,
                    SecurityAuditOutcome.SUCCESS,
                    null);
        } catch (TransactionalEmailDeliveryException exception) {
            recordFailure(userId, exception.reasonCode());
        }
    }

    void recordDispatchFailure(Long userId) {
        recordFailure(userId, "DELIVERY_DISPATCH_REJECTED");
    }

    private void recordFailure(Long userId, String reasonCode) {
        audit.record(
                SecurityAuditEventType.ACCOUNT_DELETION_SEND_FAILED,
                null,
                userId,
                null,
                null,
                null,
                SecurityAuditOutcome.FAILURE,
                reasonCode);
    }
}
