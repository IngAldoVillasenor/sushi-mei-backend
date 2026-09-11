package com.cardovia.merkon.backend.security;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Enumeration-safe public password-recovery facade. */
@Service
class PasswordRecoveryService {

    private final EmailVerificationTokenGenerator tokens;
    private final RegistrationRateLimitService rateLimit;
    private final PasswordRecoveryRequestTransaction requests;
    private final PasswordRecoveryConfirmationTransaction confirmations;
    private final PasswordRecoveryDeliveryService delivery;
    private final SecurityAuditService audit;

    PasswordRecoveryService(EmailVerificationTokenGenerator tokens,
                            RegistrationRateLimitService rateLimit,
                            PasswordRecoveryRequestTransaction requests,
                            PasswordRecoveryConfirmationTransaction confirmations,
                            PasswordRecoveryDeliveryService delivery,
                            SecurityAuditService audit) {
        this.tokens = tokens;
        this.rateLimit = rateLimit;
        this.requests = requests;
        this.confirmations = confirmations;
        this.delivery = delivery;
        this.audit = audit;
    }

    PasswordRecoveryResponse request(PasswordRecoveryRequest request, String clientIp) {
        String canonicalEmail = PublicRegistrationInput.canonicalPublicEmailOrNull(request == null ? null : request.email());
        if (canonicalEmail == null) {
            throw invalidRequest();
        }
        try {
            rateLimit.checkPasswordRecoveryRequest(canonicalEmail);
        } catch (SecurityApiException exception) {
            if ("PASSWORD_RECOVERY_RATE_LIMITED".equals(exception.code())) {
                rejectRequest(clientIp, "RATE_LIMITED");
            }
            throw exception;
        }

        EmailVerificationTokenGenerator.TokenMaterial material = tokens.generate();
        Optional<PasswordRecoveryRequestTransaction.IssuedToken> issued = requests.issueForEligibleAccount(
                canonicalEmail, material.hash(), clientIp);
        if (issued.isEmpty()) {
            // Do not reveal whether the email was unknown, inactive, or pending.
            audit.record(
                    SecurityAuditEventType.PASSWORD_RECOVERY_REQUEST_ACCEPTED,
                    null,
                    null,
                    null,
                    null,
                    clientIp,
                    SecurityAuditOutcome.SUCCESS,
                    null);
            return PasswordRecoveryResponse.requestAccepted();
        }
        PasswordRecoveryRequestTransaction.IssuedToken token = issued.orElseThrow();
        delivery.dispatch(token.token(), token.user().getId(), token.user().getEmail(), material.plaintext(), clientIp);
        return PasswordRecoveryResponse.requestAccepted();
    }

    void confirm(PasswordRecoveryConfirmRequest request, String clientIp) {
        String tokenHash;
        try {
            tokenHash = tokens.hash(request == null ? null : request.token());
        } catch (IllegalArgumentException exception) {
            rejectConfirmation(clientIp, "INVALID_TOKEN");
            throw invalidToken();
        }
        try {
            confirmations.confirm(tokenHash, request.newPassword(), clientIp);
        } catch (PasswordRecoveryConfirmationTransaction.ResetRejected exception) {
            rejectConfirmation(clientIp, exception.reasonCode());
            throw invalidToken();
        } catch (SecurityApiException exception) {
            if ("AUTH_PASSWORD_REJECTED".equals(exception.code())) {
                rejectConfirmation(clientIp, "PASSWORD_REJECTED");
            }
            throw exception;
        }
    }

    private void rejectRequest(String clientIp, String reasonCode) {
        audit.record(
                SecurityAuditEventType.PASSWORD_RECOVERY_REQUEST_REJECTED,
                null,
                null,
                null,
                null,
                clientIp,
                SecurityAuditOutcome.FAILURE,
                reasonCode);
    }

    private void rejectConfirmation(String clientIp, String reasonCode) {
        audit.record(
                SecurityAuditEventType.PASSWORD_RECOVERY_REJECTED,
                null,
                null,
                null,
                null,
                clientIp,
                SecurityAuditOutcome.FAILURE,
                reasonCode);
    }

    private static SecurityApiException invalidRequest() {
        return new SecurityApiException(
                "PASSWORD_RECOVERY_INVALID_REQUEST", HttpStatus.BAD_REQUEST,
                "Solicitud de restablecimiento inv\u00e1lida.");
    }

    private static SecurityApiException invalidToken() {
        return new SecurityApiException(
                "PASSWORD_RECOVERY_INVALID_TOKEN", HttpStatus.BAD_REQUEST,
                "El enlace de restablecimiento no es v\u00e1lido o ha vencido.");
    }
}
