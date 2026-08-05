package com.sushimei.sushimei.backend.checkout;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Explicit compatibility boundary for the temporary legacy DOUBLE and parallel
 * NUMERIC monetary columns. It owns neither persistence nor transactions.
 */
@Component
public class ParallelMoneyResolver {

    private final CheckoutMoney checkoutMoney;

    public ParallelMoneyResolver(CheckoutMoney checkoutMoney) {
        this.checkoutMoney = Objects.requireNonNull(checkoutMoney, "checkoutMoney must not be null");
    }

    public BigDecimal resolve(BigDecimal numericAmount, Double legacyAmount) {
        if (numericAmount == null && legacyAmount == null) {
            throw new MonetaryCompatibilityException(MonetaryCompatibilityReason.BOTH_REPRESENTATIONS_ABSENT);
        }

        BigDecimal normalizedNumeric = numericAmount == null ? null : normalizeNumeric(numericAmount);
        BigDecimal normalizedLegacy = legacyAmount == null ? null : normalizeLegacy(legacyAmount);

        if (normalizedNumeric != null && normalizedLegacy != null
                && normalizedNumeric.compareTo(normalizedLegacy) != 0) {
            throw new MonetaryCompatibilityException(MonetaryCompatibilityReason.REPRESENTATIONS_DISAGREE);
        }

        return normalizedNumeric != null ? normalizedNumeric : normalizedLegacy;
    }

    public ParallelMoney forWriteFromLegacy(Double legacyAmount) {
        BigDecimal normalized = normalizeLegacy(legacyAmount);
        return new ParallelMoney(normalized, legacyAmount);
    }

    public ParallelMoney forWriteFromExact(BigDecimal exactAmount) {
        BigDecimal normalized = normalizeNumeric(exactAmount);
        double legacyAmount = normalized.doubleValue();

        if (!Double.isFinite(legacyAmount)) {
            throw new MonetaryCompatibilityException(MonetaryCompatibilityReason.INVALID_LEGACY_REPRESENTATION);
        }

        BigDecimal normalizedRoundTrip = normalizeLegacy(legacyAmount);
        if (normalized.compareTo(normalizedRoundTrip) != 0) {
            throw new MonetaryCompatibilityException(MonetaryCompatibilityReason.INVALID_LEGACY_REPRESENTATION);
        }

        return new ParallelMoney(normalized, legacyAmount);
    }

    private BigDecimal normalizeNumeric(BigDecimal amount) {
        try {
            return checkoutMoney.normalizeNumericAmount(amount);
        } catch (IllegalArgumentException exception) {
            throw new MonetaryCompatibilityException(MonetaryCompatibilityReason.INVALID_NUMERIC_REPRESENTATION, exception);
        }
    }

    private BigDecimal normalizeLegacy(Double amount) {
        try {
            return checkoutMoney.normalizeLegacyAmount(amount);
        } catch (IllegalArgumentException exception) {
            throw new MonetaryCompatibilityException(MonetaryCompatibilityReason.INVALID_LEGACY_REPRESENTATION, exception);
        }
    }
}
