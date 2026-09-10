package com.cardovia.merkon.backend.security;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Enumeration-safe public registration facade. Duplicate identities receive
 * the same response as an accepted new registration, while unexpected
 * persistence failures continue through normal error handling.
 */
@Service
public class PublicRegistrationService {

    private final AppUserRepository users;
    private final PasswordPolicyService passwords;
    private final RegistrationRateLimitService rateLimit;
    private final PublicRegistrationCreationTransaction creation;
    private final SecurityAuditService audit;

    PublicRegistrationService(AppUserRepository users,
                              PasswordPolicyService passwords,
                              RegistrationRateLimitService rateLimit,
                              PublicRegistrationCreationTransaction creation,
                              SecurityAuditService audit) {
        this.users = users;
        this.passwords = passwords;
        this.rateLimit = rateLimit;
        this.creation = creation;
        this.audit = audit;
    }

    public PublicRegistrationResponse register(PublicRegistrationRequest request, String clientIp) {
        PublicRegistrationInput input = PublicRegistrationInput.from(request);
        try {
            rateLimit.checkCanonicalIdentity(input.normalizedEmail());
        } catch (SecurityApiException exception) {
            if ("REGISTRATION_RATE_LIMITED".equals(exception.code())) {
                recordRejected(clientIp, "RATE_LIMITED");
            }
            throw exception;
        }
        // Validate and perform BCrypt work before duplicate detection so the
        // ordinary duplicate path is not a cheap account-existence oracle.
        String passwordHash = passwords.encodeValidated(input.normalizedEmail(), request.password());
        if (identityAlreadyReserved(input.normalizedEmail())) {
            recordRejected(clientIp, "DUPLICATE_IDENTITY");
            return PublicRegistrationResponse.accepted();
        }
        try {
            creation.create(input, passwordHash, clientIp);
            return PublicRegistrationResponse.accepted();
        } catch (DataIntegrityViolationException exception) {
            // A concurrent winner may have created the exact public identity
            // after the preflight. Only that known condition is made generic.
            if (identityAlreadyReserved(input.normalizedEmail())) {
                recordRejected(clientIp, "DUPLICATE_IDENTITY");
                return PublicRegistrationResponse.accepted();
            }
            throw exception;
        }
    }

    private boolean identityAlreadyReserved(String normalizedEmail) {
        return users.existsByUsernameOrEmailAliasIgnoreCase(
                PublicEmailIdentity.require(normalizedEmail).aliases());
    }

    private void recordRejected(String clientIp, String reasonCode) {
        audit.record(
                SecurityAuditEventType.REGISTRATION_REJECTED,
                null,
                null,
                null,
                null,
                clientIp,
                SecurityAuditOutcome.FAILURE,
                reasonCode);
    }
}
