package com.cardovia.merkon.backend.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/** Reuses the application-owned transactional sender and bounded post-commit executor. */
@Service
class PasswordRecoveryDeliveryService {

    private final TaskExecutor executor;
    private final TransactionalEmailSender sender;
    private final PasswordRecoveryEmailTemplate template;
    private final SecurityAuditService audit;

    PasswordRecoveryDeliveryService(@Qualifier("emailVerificationDeliveryExecutor") TaskExecutor executor,
                                    TransactionalEmailSender sender,
                                    PasswordRecoveryEmailTemplate template,
                                    SecurityAuditService audit) {
        this.executor = executor;
        this.sender = sender;
        this.template = template;
        this.audit = audit;
    }

    void dispatch(PasswordResetToken token,
                  Long userId,
                  String recipient,
                  String plaintextToken,
                  String clientIp) {
        try {
            executor.execute(() -> deliver(token, userId, recipient, plaintextToken, clientIp));
        } catch (TaskRejectedException exception) {
            recordFailure(userId, clientIp, "DELIVERY_DISPATCH_REJECTED");
        }
    }

    private void deliver(PasswordResetToken token,
                         Long userId,
                         String recipient,
                         String plaintextToken,
                         String clientIp) {
        try {
            sender.send(template.render(recipient, plaintextToken, token.getExpiresAt(), token.getId()));
            audit.record(
                    SecurityAuditEventType.PASSWORD_RECOVERY_SEND_SUCCEEDED,
                    null,
                    userId,
                    null,
                    null,
                    clientIp,
                    SecurityAuditOutcome.SUCCESS,
                    null);
        } catch (TransactionalEmailDeliveryException exception) {
            recordFailure(userId, clientIp, exception.reasonCode());
        }
    }

    private void recordFailure(Long userId, String clientIp, String reasonCode) {
        audit.record(
                SecurityAuditEventType.PASSWORD_RECOVERY_SEND_FAILED,
                null,
                userId,
                null,
                null,
                clientIp,
                SecurityAuditOutcome.FAILURE,
                reasonCode);
    }
}
