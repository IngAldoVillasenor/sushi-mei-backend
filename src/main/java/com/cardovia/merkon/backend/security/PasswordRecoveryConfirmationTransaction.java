package com.cardovia.merkon.backend.security;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes a reset token under the same lock ordering used for replacement:
 * AppUser first, then PasswordResetToken. Session revocation is part of the
 * same successful password-change transaction.
 */
@Component
class PasswordRecoveryConfirmationTransaction {

    private final AppUserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordPolicyService passwords;
    private final AuthSessionService sessions;
    private final SecurityAuditService audit;
    private final Clock clock;

    PasswordRecoveryConfirmationTransaction(AppUserRepository users,
                                            PasswordResetTokenRepository tokens,
                                            PasswordPolicyService passwords,
                                            AuthSessionService sessions,
                                            SecurityAuditService audit,
                                            Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    void confirm(String tokenHash, String newPassword, String clientIp) {
        // The preliminary scalar read authorizes no mutation. It only gives us
        // the owner needed to take the deterministic user -> token lock order.
        Long userId = tokens.findUserIdByTokenHash(tokenHash)
                .orElseThrow(() -> ResetRejected.invalid("INVALID_TOKEN"));
        AppUser user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> ResetRejected.invalid("INVALID_TOKEN"));
        PasswordResetToken token = tokens.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> ResetRejected.invalid("INVALID_TOKEN"));
        if (!token.getUser().getId().equals(user.getId()) || !user.isActive()
                || user.getRegistrationState() == AccountRegistrationState.PENDING_EMAIL_VERIFICATION) {
            throw ResetRejected.invalid("INVALID_TOKEN");
        }

        Instant now = clock.instant();
        PasswordResetToken.TokenUseResult result = token.consume(now);
        if (result != PasswordResetToken.TokenUseResult.CONSUMED) {
            throw ResetRejected.invalid(result.name());
        }

        String encodedPassword = passwords.encodeValidated(user.getUsername(), newPassword);
        user.resetPassword(encodedPassword, now);
        tokens.findActiveByUserIdForUpdate(user.getId()).forEach(candidate -> candidate.revoke(now));
        sessions.revokeAll(user.getId(), "PASSWORD_RECOVERY", null, clientIp);
        audit.record(
                SecurityAuditEventType.PASSWORD_RECOVERY_COMPLETED,
                null,
                user.getId(),
                null,
                null,
                clientIp,
                SecurityAuditOutcome.SUCCESS,
                null);
    }

    static final class ResetRejected extends RuntimeException {
        private final String reasonCode;

        private ResetRejected(String reasonCode) {
            this.reasonCode = reasonCode;
        }

        static ResetRejected invalid(String reasonCode) {
            return new ResetRejected(reasonCode);
        }

        String reasonCode() {
            return reasonCode;
        }
    }
}
