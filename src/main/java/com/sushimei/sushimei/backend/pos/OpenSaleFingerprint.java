package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/** Price-inclusive digest because price is an explicit, authorized Open Sale input. */
@Component
class OpenSaleFingerprint {
    String fingerprint(String description, java.math.BigDecimal amount, OrderPaymentMethod paymentMethod,
                       java.math.BigDecimal cashDenomination) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = description + "\\u001f" + amount.toPlainString() + "\\u001f" + paymentMethod.name()
                    + "\\u001f" + (cashDenomination == null ? "" : cashDenomination.toPlainString());
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
