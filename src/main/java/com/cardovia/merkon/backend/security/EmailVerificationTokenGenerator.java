package com.cardovia.merkon.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Generates 256-bit URL-safe public tokens and hashes their exact wire representation. */
@Component
class EmailVerificationTokenGenerator {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    TokenMaterial generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new TokenMaterial(plaintext, hash(plaintext));
    }

    String hash(String plaintext) {
        if (plaintext == null || plaintext.isBlank() || plaintext.length() > 512) {
            throw new IllegalArgumentException("Invalid verification token");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record TokenMaterial(String plaintext, String hash) {
    }
}
