package com.cardovia.merkon.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenService {

    private static final int SECRET_BYTES = 32;
    private static final String MERKON_PREFIX = "mkr_";
    private static final String LEGACY_SUSHIMEI_PREFIX = "smr_";

    private final SecureRandom secureRandom = new SecureRandom();

    public String issue(UUID sessionId) {
        byte[] secret = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secret);
        return MERKON_PREFIX + sessionId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    public Parsed parse(String token) {
        try {
            if (token == null || !(token.startsWith(MERKON_PREFIX) || token.startsWith(LEGACY_SUSHIMEI_PREFIX))) {
                return null;
            }
            String[] parts = token.substring(4).split("\\.", -1);
            if (parts.length != 2 || parts[1].isBlank()) {
                return null;
            }
            return new Parsed(UUID.fromString(parts[0]), hash(token));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Parsed(UUID sessionId, String hash) {
    }
}
