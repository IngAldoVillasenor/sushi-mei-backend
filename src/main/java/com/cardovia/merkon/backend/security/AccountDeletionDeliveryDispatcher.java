package com.cardovia.merkon.backend.security;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/** Bounded asynchronous boundary that removes provider latency from the public response. */
@Service
class AccountDeletionDeliveryDispatcher {

    private final TaskExecutor executor;
    private final AccountDeletionDeliveryService delivery;

    AccountDeletionDeliveryDispatcher(@Qualifier("emailVerificationDeliveryExecutor") TaskExecutor executor,
                                      AccountDeletionDeliveryService delivery) {
        this.executor = executor;
        this.delivery = delivery;
    }

    void dispatch(Long userId,
                  String recipient,
                  UUID requestId,
                  Instant expiresAt,
                  String plaintextToken) {
        try {
            executor.execute(() -> delivery.deliver(userId, recipient, requestId, expiresAt, plaintextToken));
        } catch (TaskRejectedException exception) {
            delivery.recordDispatchFailure(userId);
        }
    }
}
