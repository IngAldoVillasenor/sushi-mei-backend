package com.sushimei.sushimei.backend.checkout;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Objects;

/**
 * Deterministic money policy for checkout reads. Legacy floating-point values are
 * converted once at this compatibility boundary and are never used for arithmetic.
 */
@Component
public class CheckoutMoney {

    public static final int PRECISION = 19;
    public static final int SCALE = 2;

    public BigDecimal normalizeLegacyUnitPrice(Double legacyUnitPrice) {
        if (legacyUnitPrice == null || !Double.isFinite(legacyUnitPrice)) {
            throw new IllegalArgumentException("Unit price must be a finite value.");
        }

        return normalizePositiveAmount(BigDecimal.valueOf(legacyUnitPrice));
    }

    public int requirePositiveQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive.");
        }
        return quantity;
    }

    public BigDecimal calculateLineTotal(int quantity, BigDecimal unitPrice) {
        requirePositiveQuantity(quantity);
        BigDecimal normalizedUnitPrice = normalizePositiveAmount(unitPrice);
        return normalizeCalculatedTotal(normalizedUnitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    public BigDecimal calculateCartTotal(Collection<BigDecimal> lineTotals) {
        Objects.requireNonNull(lineTotals, "lineTotals must not be null");

        BigDecimal total = BigDecimal.ZERO.setScale(SCALE);
        for (BigDecimal lineTotal : lineTotals) {
            if (lineTotal == null || lineTotal.signum() <= 0) {
                throw new IllegalArgumentException("Line total must be positive.");
            }
            total = total.add(lineTotal);
        }

        return normalizeCalculatedTotal(total);
    }

    private BigDecimal normalizePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive.");
        }

        return normalizeExact(amount, IllegalArgumentException::new);
    }

    private BigDecimal normalizeCalculatedTotal(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ArithmeticException("Calculated total must be positive.");
        }

        return normalizeExact(amount, ArithmeticException::new);
    }

    private BigDecimal normalizeExact(BigDecimal amount, MessageExceptionFactory exceptionFactory) {
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw exceptionFactory.create("Amount has more than two meaningful fractional digits.");
        }

        final BigDecimal normalized;
        try {
            normalized = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw exceptionFactory.create("Amount cannot be represented exactly at the required scale.");
        }

        if (normalized.precision() > PRECISION) {
            throw exceptionFactory.create("Amount exceeds deterministic checkout precision.");
        }
        return normalized;
    }

    @FunctionalInterface
    private interface MessageExceptionFactory {
        RuntimeException create(String message);
    }
}
