package com.sushimei.sushimei.backend.businessday;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

@Component
class CashExpenseFingerprint {

    String fingerprint(BigDecimal amount, String description, String note) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = amount.toPlainString() + "\u001f" + description + "\u001f"
                    + (note == null ? "" : note);
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
