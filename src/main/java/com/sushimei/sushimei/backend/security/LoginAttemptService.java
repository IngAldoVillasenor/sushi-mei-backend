package com.sushimei.sushimei.backend.security;

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
        if (locked || !user.isActive() || !passwordMatches) {
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
                    locked ? "LOCKED" : (!user.isActive() ? "INACTIVE" : "INVALID_CREDENTIALS"));
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
        return LoginEvaluation.success(user.getId());
    }

    public record LoginEvaluation(boolean success, Long userId) {
        static LoginEvaluation failure() {
            return new LoginEvaluation(false, null);
        }

        static LoginEvaluation success(Long userId) {
            return new LoginEvaluation(true, userId);
        }
    }
}