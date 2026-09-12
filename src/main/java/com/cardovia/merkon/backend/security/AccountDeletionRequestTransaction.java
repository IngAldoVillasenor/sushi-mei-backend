package com.cardovia.merkon.backend.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The durable public request unit. It serializes replacement by locking the
 * user row before pending request rows and returns only after its transaction
 * has committed, so delivery can never run for rolled-back state.
 */
@Component
class AccountDeletionRequestTransaction {

    private static final Duration TOKEN_TTL = Duration.ofHours(1);

    private final AppUserRepository users;
    private final AccountDeletionRequestRepository requests;
    private final SecurityAuditService audit;
    private final Clock clock;

    AccountDeletionRequestTransaction(AppUserRepository users,
                                      AccountDeletionRequestRepository requests,
                                      SecurityAuditService audit,
                                      Clock clock) {
        this.users = users;
        this.requests = requests;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    Optional<IssuedRequest> issueForExistingAccount(String canonicalEmail,
                                                     String tokenHash,
                                                     String clientIp) {
        AppUser user = users.findByEmailForUpdate(canonicalEmail).orElse(null);
        if (user == null || user.getRegistrationState() == AccountRegistrationState.DELETED
                || user.getEmail() == null || user.getEmail().isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        requests.findPendingByUserIdForUpdate(user.getId()).forEach(request -> request.supersede(now));
        AccountDeletionRequest request = requests.saveAndFlush(AccountDeletionRequest.pending(
                user, AccountDeletionSource.PUBLIC, tokenHash, now, now.plus(TOKEN_TTL)));
        audit.record(
                SecurityAuditEventType.ACCOUNT_DELETION_REQUEST_ACCEPTED,
                null,
                user.getId(),
                null,
                null,
                clientIp,
                SecurityAuditOutcome.SUCCESS,
                null);
        return Optional.of(new IssuedRequest(user.getId(), user.getEmail(), request.getId(), request.getExpiresAt()));
    }

    record IssuedRequest(Long userId, String recipient, java.util.UUID requestId, Instant expiresAt) {
    }
}
