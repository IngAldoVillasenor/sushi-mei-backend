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

    RegistrationRateLimitService(RegistrationRateLimitTransaction transaction,
                                 PublicRegistrationProperties properties) {
        this.transaction = transaction;
        this.properties = properties;
    }

    void checkTransportAddress(String observedConnectionAddress) {
        check(bucketKey("merkon-registration-transport-v2:", observedConnectionAddress),
                properties.transportRateLimitMaxAttempts());
    }

    void checkCanonicalIdentity(String canonicalEmail) {
        check(bucketKey("merkon-registration-identity-v2:", canonicalEmail),
                properties.rateLimitMaxAttempts());
    }

    private void check(String bucketKey, int maximumAttempts) {
        for (int attempt = 0; attempt <= H2_CREATION_RACE_RETRIES; attempt++) {
            try {
                if (!transaction.recordAttempt(bucketKey, maximumAttempts)) {
                    throw rateLimited();
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

    private static SecurityApiException rateLimited() {
        return new SecurityApiException(
                "REGISTRATION_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
                "Demasiadas solicitudes de registro. Intentalo mas tarde.");
    }
}
