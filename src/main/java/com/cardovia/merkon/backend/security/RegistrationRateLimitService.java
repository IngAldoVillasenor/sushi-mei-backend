package com.cardovia.merkon.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class RegistrationRateLimitService {

    private static final int H2_CREATION_RACE_RETRIES = 2;

    private final RegistrationRateLimitTransaction transaction;
    private final PublicRegistrationProperties properties;
    private final EmailVerificationProperties verificationProperties;

    RegistrationRateLimitService(RegistrationRateLimitTransaction transaction,
                                 PublicRegistrationProperties properties,
                                 EmailVerificationProperties verificationProperties) {
        this.transaction = transaction;
        this.properties = properties;
        this.verificationProperties = verificationProperties;
    }

    void checkTransportAddress(String observedConnectionAddress) {
        check(bucketKey("merkon-registration-transport-v2:", observedConnectionAddress),
                properties.transportRateLimitMaxAttempts(), properties.rateLimitWindow(),
                "REGISTRATION_RATE_LIMITED", "Demasiadas solicitudes de registro. Inténtalo más tarde.");
    }

    void checkCanonicalIdentity(String canonicalEmail) {
        check(bucketKey("merkon-registration-identity-v2:", canonicalEmail),
                properties.rateLimitMaxAttempts(), properties.rateLimitWindow(),
                "REGISTRATION_RATE_LIMITED", "Demasiadas solicitudes de registro. Inténtalo más tarde.");
    }

    void checkEmailVerificationResend(String canonicalEmail) {
        check(bucketKey("merkon-email-verification-resend-v1:", canonicalEmail),
                verificationProperties.resendRateLimitMaxAttempts(), verificationProperties.resendRateLimitWindow(),
                "EMAIL_VERIFICATION_RESEND_RATE_LIMITED", "Demasiadas solicitudes de reenvío. Inténtalo más tarde.");
    }

    void checkEmailVerificationTransport(String observedConnectionAddress) {
        check(bucketKey("merkon-email-verification-transport-v1:", observedConnectionAddress),
                verificationProperties.transportRateLimitMaxAttempts(), verificationProperties.transportRateLimitWindow(),
                "EMAIL_VERIFICATION_TRANSPORT_RATE_LIMITED", "Demasiadas solicitudes de verificación. Inténtalo más tarde.");
    }

    private void check(String bucketKey,
                       int maximumAttempts,
                       java.time.Duration window,
                       String errorCode,
                       String errorMessage) {
        for (int attempt = 0; attempt <= H2_CREATION_RACE_RETRIES; attempt++) {
            try {
                if (!transaction.recordAttempt(bucketKey, maximumAttempts, window)) {
                    throw rateLimited(errorCode, errorMessage);
                }
                return;
            } catch (RegistrationRateLimitTransaction.BucketCreationRaceException exception) {
                if (attempt == H2_CREATION_RACE_RETRIES) {
                    throw exception;
                }
            }
        }
    }

    static String bucketKey(String domainSeparator, String value) {
        String source = value == null ? "" : value.strip();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((domainSeparator + source).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static SecurityApiException rateLimited(String code, String message) {
        return new SecurityApiException(
                code, HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
