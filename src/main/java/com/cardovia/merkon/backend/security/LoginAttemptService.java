package com.cardovia.merkon.backend.security;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginAttemptService {

    private final AppUserRepository users;
    private final PasswordPolicyService passwords;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final String dummyHash;

    public LoginAttemptService(AppUserRepository users,
                               PasswordPolicyService passwords,
                               SecurityAuditService audit,
                               Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
        this.clock = clock;
        this.dummyHash = passwords.encodeValidated(
                "dummy-account",
                "unrelated secure verification phrase cinnamon");
    }

    /**
     * Commits login security state and its audit record before the caller maps
     * the result to the deliberately generic public authentication response.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LoginEvaluation evaluate(String username, String password, String clientIp) {
        Instant now = clock.instant();
        AppUser user = users.findByUsernameForUpdate(username).orElse(null);
        if (user == null) {
            String canonicalPublicEmail = PublicRegistrationInput.canonicalPublicEmailOrNull(username);
            if (canonicalPublicEmail != null && !canonicalPublicEmail.equals(username)) {
                // Preserve a legacy username verbatim first; only then resolve the
                // canonical public-email form used by self-service registrations.
                user = users.findByUsernameForUpdate(canonicalPublicEmail).orElse(null);
            }
        }
        if (user == null) {
            passwords.matches(password, dummyHash);
            audit.record(
                    SecurityAuditEventType.LOGIN_FAILURE,
                    null,
                    null,
                    null,
                    null,
                    clientIp,
                    SecurityAuditOutcome.FAILURE,
                    "UNKNOWN_USERNAME");
            return LoginEvaluation.failure();
        }

        boolean locked = user.getLockedUntil() != null && now.isBefore(user.getLockedUntil());
        boolean passwordMatches = passwords.matches(password, user.getPasswordHash());
        boolean pendingEmailVerification = user.getRegistrationState() == AccountRegistrationState.PENDING_EMAIL_VERIFICATION;
        if (locked || !user.isActive() || pendingEmailVerification || !passwordMatches) {
            if (!locked) {
                user.recordFailure(now);
            }
            audit.record(
                    SecurityAuditEventType.LOGIN_FAILURE,
                    user.getId(),
                    user.getId(),
                    null,
                    null,
                    clientIp,
                    SecurityAuditOutcome.FAILURE,
                    locked ? "LOCKED" : ((!user.isActive() || pendingEmailVerification) ? "INACTIVE" : "INVALID_CREDENTIALS"));
            return LoginEvaluation.failure();
        }

        user.recordSuccess(now);
        audit.record(
                SecurityAuditEventType.LOGIN_SUCCESS,
                user.getId(),
                user.getId(),
                null,
                null,
                clientIp,
                SecurityAuditOutcome.SUCCESS,
                null);
        // This is an in-process credential snapshot, never exposed in an API
        // response or audit record. AuthSessionService rechecks it under the
        // user write lock before creating a session so an authentication that
        // was evaluated before a password change cannot open a session later.
        return LoginEvaluation.success(user.getId(), user.getPasswordHash());
    }

    public record LoginEvaluation(boolean success, Long userId, String passwordHashSnapshot) {
        static LoginEvaluation failure() {
            return new LoginEvaluation(false, null, null);
        }

        static LoginEvaluation success(Long userId, String passwordHashSnapshot) {
            return new LoginEvaluation(true, userId, passwordHashSnapshot);
        }
    }
}
