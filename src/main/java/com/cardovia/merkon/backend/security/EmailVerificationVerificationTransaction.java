package com.cardovia.merkon.backend.security;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Locks a one-time token before consuming it and activating the pending account. */
@Component
class EmailVerificationVerificationTransaction {

    private final AppUserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final SecurityAuditService audit;
    private final Clock clock;

    EmailVerificationVerificationTransaction(AppUserRepository users,
                                             EmailVerificationTokenRepository tokens,
                                             SecurityAuditService audit,
                                             Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    VerifiedAccount verify(String tokenHash, String clientIp) {
        // The preliminary read authorizes no mutable state. Both verification
        // and resend then take locks in the same deterministic order:
        // AppUser first, followed by EmailVerificationToken.
        Long userId = tokens.findUserIdByTokenHash(tokenHash)
                .orElseThrow(() -> VerificationRejected.invalid("INVALID_TOKEN"));
        AppUser user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> VerificationRejected.invalid("INVALID_TOKEN"));
        EmailVerificationToken token = tokens.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> VerificationRejected.invalid("INVALID_TOKEN"));
        if (!token.getUser().getId().equals(user.getId())) {
            throw VerificationRejected.invalid("INVALID_TOKEN");
        }
        Instant now = clock.instant();
        EmailVerificationToken.TokenUseResult result = token.consume(now);
        if (result != EmailVerificationToken.TokenUseResult.CONSUMED) {
            throw VerificationRejected.invalid(result.name());
        }
        try {
            user.activateVerifiedRegistration(now);
        } catch (IllegalStateException exception) {
            throw VerificationRejected.invalid("ACCOUNT_NOT_PENDING");
        }
        audit.record(
                SecurityAuditEventType.EMAIL_VERIFICATION_SUCCEEDED,
                null,
                user.getId(),
                null,
                null,
                clientIp,
                SecurityAuditOutcome.SUCCESS,
                null);
        return new VerifiedAccount(user.getId(), token.getId());
    }

    record VerifiedAccount(Long userId, Long tokenId) {
    }

    static final class VerificationRejected extends RuntimeException {
        private final String reasonCode;

        private VerificationRejected(String reasonCode) {
            this.reasonCode = reasonCode;
        }

        static VerificationRejected invalid(String reasonCode) {
            return new VerificationRejected(reasonCode);
        }

        String reasonCode() {
            return reasonCode;
        }
    }
}
