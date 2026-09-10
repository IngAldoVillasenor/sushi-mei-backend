package com.cardovia.merkon.backend.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Serializes resends per pending user and leaves only the newest token usable. */
@Component
class EmailVerificationResendTransaction {

    private final AppUserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final EmailVerificationProperties properties;
    private final SecurityAuditService audit;
    private final Clock clock;

    EmailVerificationResendTransaction(AppUserRepository users,
                                       EmailVerificationTokenRepository tokens,
                                       EmailVerificationProperties properties,
                                       SecurityAuditService audit,
                                       Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    Optional<IssuedToken> replacePendingToken(String canonicalEmail,
                                              String tokenHash,
                                              String clientIp) {
        AppUser user = users.findByEmailForUpdate(canonicalEmail).orElse(null);
        if (user == null || user.getRegistrationState() != AccountRegistrationState.PENDING_EMAIL_VERIFICATION
                || user.isActive()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        tokens.findActiveByUserIdForUpdate(user.getId()).forEach(token -> token.revoke(now));
        EmailVerificationToken token = tokens.saveAndFlush(EmailVerificationToken.issue(
                user, tokenHash, now, now.plus(properties.tokenTtl())));
        audit.record(
                SecurityAuditEventType.EMAIL_VERIFICATION_RESEND_ACCEPTED,
                null,
                user.getId(),
                null,
                null,
                clientIp,
                SecurityAuditOutcome.SUCCESS,
                null);
        return Optional.of(new IssuedToken(user, token));
    }

    record IssuedToken(AppUser user, EmailVerificationToken token) {
    }
}
