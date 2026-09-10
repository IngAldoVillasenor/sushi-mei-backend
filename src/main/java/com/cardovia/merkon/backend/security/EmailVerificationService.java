package com.cardovia.merkon.backend.security;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class EmailVerificationService {

    private final EmailVerificationTokenGenerator tokens;
    private final EmailVerificationVerificationTransaction verification;
    private final EmailVerificationResendTransaction resend;
    private final RegistrationRateLimitService rateLimit;
    private final EmailVerificationDeliveryDispatcher delivery;
    private final SecurityAuditService audit;

    EmailVerificationService(EmailVerificationTokenGenerator tokens,
                             EmailVerificationVerificationTransaction verification,
                             EmailVerificationResendTransaction resend,
                             RegistrationRateLimitService rateLimit,
                             EmailVerificationDeliveryDispatcher delivery,
                             SecurityAuditService audit) {
        this.tokens = tokens;
        this.verification = verification;
        this.resend = resend;
        this.rateLimit = rateLimit;
        this.delivery = delivery;
        this.audit = audit;
    }

    EmailVerificationResponse verify(EmailVerificationRequest request, String clientIp) {
        String tokenHash;
        try {
            tokenHash = tokens.hash(request == null ? null : request.token());
        } catch (IllegalArgumentException exception) {
            rejectVerification(clientIp, "INVALID_TOKEN");
            throw invalidToken();
        }
        try {
            verification.verify(tokenHash, clientIp);
            return EmailVerificationResponse.verified();
        } catch (EmailVerificationVerificationTransaction.VerificationRejected exception) {
            rejectVerification(clientIp, exception.reasonCode());
            throw invalidToken();
        }
    }

    EmailVerificationResponse resend(EmailVerificationResendRequest request, String clientIp) {
        String canonicalEmail = PublicRegistrationInput.canonicalPublicEmailOrNull(request == null ? null : request.email());
        if (canonicalEmail == null) {
            throw invalidRequest();
        }
        try {
            rateLimit.checkEmailVerificationResend(canonicalEmail);
        } catch (SecurityApiException exception) {
            if ("EMAIL_VERIFICATION_RESEND_RATE_LIMITED".equals(exception.code())) {
                audit.record(
                        SecurityAuditEventType.EMAIL_VERIFICATION_RESEND_REJECTED,
                        null,
                        null,
                        null,
                        null,
                        clientIp,
                        SecurityAuditOutcome.FAILURE,
                        "RATE_LIMITED");
            }
            throw exception;
        }
        EmailVerificationTokenGenerator.TokenMaterial material = tokens.generate();
        Optional<EmailVerificationResendTransaction.IssuedToken> issued = resend.replacePendingToken(
                canonicalEmail, material.hash(), clientIp);
        if (issued.isEmpty()) {
            audit.record(
                    SecurityAuditEventType.EMAIL_VERIFICATION_RESEND_ACCEPTED,
                    null,
                    null,
                    null,
                    null,
                    clientIp,
                    SecurityAuditOutcome.SUCCESS,
                    null);
            return EmailVerificationResponse.resendAccepted();
        }
        EmailVerificationResendTransaction.IssuedToken token = issued.orElseThrow();
        delivery.dispatch(token.token(), token.user().getId(), token.user().getEmail(), material.plaintext(), clientIp);
        return EmailVerificationResponse.resendAccepted();
    }

    private void rejectVerification(String clientIp, String reasonCode) {
        audit.record(
                SecurityAuditEventType.EMAIL_VERIFICATION_REJECTED,
                null,
                null,
                null,
                null,
                clientIp,
                SecurityAuditOutcome.FAILURE,
                reasonCode);
    }

    private static SecurityApiException invalidToken() {
        return new SecurityApiException(
                "EMAIL_VERIFICATION_INVALID_TOKEN", HttpStatus.BAD_REQUEST,
                "El token de verificación no es válido.");
    }

    private static SecurityApiException invalidRequest() {
        return new SecurityApiException(
                "EMAIL_VERIFICATION_INVALID_REQUEST", HttpStatus.BAD_REQUEST,
                "Solicitud de verificación inválida.");
    }
}
