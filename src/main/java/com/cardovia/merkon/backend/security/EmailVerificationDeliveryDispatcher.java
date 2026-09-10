package com.cardovia.merkon.backend.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * Separates public HTTP timing from the external email provider after the
 * token-issuing transaction has committed. The token remains durable and the
 * anonymous resend operation is the recovery path if this best-effort task
 * cannot execute before an instance is stopped.
 */
@Service
class EmailVerificationDeliveryDispatcher {

    private final TaskExecutor executor;
    private final EmailVerificationDeliveryService delivery;

    EmailVerificationDeliveryDispatcher(
            @Qualifier("emailVerificationDeliveryExecutor") TaskExecutor executor,
            EmailVerificationDeliveryService delivery) {
        this.executor = executor;
        this.delivery = delivery;
    }

    void dispatch(EmailVerificationToken token,
                  Long userId,
                  String recipient,
                  String plaintextToken,
                  String clientIp) {
        try {
            executor.execute(() -> delivery.deliver(token, userId, recipient, plaintextToken, clientIp));
        } catch (TaskRejectedException exception) {
            delivery.recordDispatchFailure(userId, clientIp);
        }
    }
}
