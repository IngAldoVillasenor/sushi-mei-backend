package com.cardovia.merkon.backend.security;

import org.springframework.stereotype.Service;

/** Delivers only after a token-issuing transaction committed. */
@Service
class EmailVerificationDeliveryService {

    private final TransactionalEmailSender sender;
    private final EmailVerificationEmailTemplate template;
    private final SecurityAuditService audit;

    EmailVerificationDeliveryService(TransactionalEmailSender sender,
                                     EmailVerificationEmailTemplate template,
                                     SecurityAuditService audit) {
        this.sender = sender;
        this.template = template;
        this.audit = audit;
    }

    void deliver(EmailVerificationToken token,
                 Long userId,
                 String recipient,
                 String plaintextToken,
                 String clientIp) {
        try {
            sender.send(template.render(recipient, plaintextToken, token.getExpiresAt(), token.getId()));
            audit.record(
                    SecurityAuditEventType.EMAIL_VERIFICATION_SEND_SUCCEEDED,
                    null,
                    userId,
                    null,
                    null,
                    clientIp,
                    SecurityAuditOutcome.SUCCESS,
                    null);
        } catch (TransactionalEmailDeliveryException exception) {
            // The pending account and token were committed before this provider call.
            audit.record(
                    SecurityAuditEventType.EMAIL_VERIFICATION_SEND_FAILED,
                    null,
                    userId,
                    null,
                    null,
                    clientIp,
                    SecurityAuditOutcome.FAILURE,
                    exception.reasonCode());
        }
    }

    void recordDispatchFailure(Long userId, String clientIp) {
        audit.record(
                SecurityAuditEventType.EMAIL_VERIFICATION_SEND_FAILED,
                null,
                userId,
                null,
                null,
                clientIp,
                SecurityAuditOutcome.FAILURE,
                "DELIVERY_DISPATCH_REJECTED");
    }
}
