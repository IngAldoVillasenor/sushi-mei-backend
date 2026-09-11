package com.cardovia.merkon.backend.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Serializes issuance per eligible account and leaves only the newest reset token usable. */
@Component
class PasswordRecoveryRequestTransaction {

    private final AppUserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordRecoveryProperties properties;
    private final SecurityAuditService audit;
    private final Clock clock;

    PasswordRecoveryRequestTransaction(AppUserRepository users,
                                       PasswordResetTokenRepository tokens,
                                       PasswordRecoveryProperties properties,
                                       SecurityAuditService audit,
                                       Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    Optional<IssuedToken> issueForEligibleAccount(String canonicalEmail, String tokenHash, String clientIp) {
        AppUser user = users.findByEmailForUpdate(canonicalEmail).orElse(null);
        if (!eligible(user)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        tokens.findActiveByUserIdForUpdate(user.getId()).forEach(token -> token.revoke(now));
        PasswordResetToken token = tokens.saveAndFlush(PasswordResetToken.issue(
                user, tokenHash, now, now.plus(properties.tokenTtl())));
        audit.record(
                SecurityAuditEventType.PASSWORD_RECOVERY_REQUEST_ACCEPTED,
                null,
                user.getId(),
                null,
                null,
                clientIp,
                SecurityAuditOutcome.SUCCESS,
                null);
        return Optional.of(new IssuedToken(user, token));
    }

    private static boolean eligible(AppUser user) {
        return user != null && user.isActive()
                && user.getRegistrationState() != AccountRegistrationState.PENDING_EMAIL_VERIFICATION
                && user.getEmail() != null && !user.getEmail().isBlank();
    }

    record IssuedToken(AppUser user, PasswordResetToken token) {
    }
}
