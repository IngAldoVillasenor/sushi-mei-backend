package com.sushimei.sushimei.backend.checkout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record CartLineSnapshot(
        Long cartItemId,
        String dishName,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal) {

    private static final int MAX_DISH_NAME_LENGTH = 255;

    public CartLineSnapshot {
        Objects.requireNonNull(cartItemId, "cartItemId must not be null");
        dishName = normalizeDishName(dishName);
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        unitPrice = requireNormalizedPositiveAmount(unitPrice, "unitPrice");
        lineTotal = requireNormalizedPositiveAmount(lineTotal, "lineTotal");

        BigDecimal expectedLineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity))
                .setScale(CheckoutMoney.SCALE, RoundingMode.UNNECESSARY);
        if (!expectedLineTotal.equals(lineTotal)) {
            throw new IllegalArgumentException("lineTotal must equal quantity multiplied by unitPrice");
        }
    }

    private static String normalizeDishName(String value) {
        if (value == null) {
            throw new IllegalArgumentException("dishName must not be blank");
        }

        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_DISH_NAME_LENGTH) {
            throw new IllegalArgumentException("dishName is outside the supported length");
        }
        return normalized;
    }

    private static BigDecimal requireNormalizedPositiveAmount(BigDecimal amount, String fieldName) {
        Objects.requireNonNull(amount, fieldName + " must not be null");
        if (amount.signum() <= 0
                || amount.scale() != CheckoutMoney.SCALE
                || amount.precision() > CheckoutMoney.PRECISION) {
            throw new IllegalArgumentException(fieldName + " must be a normalized positive checkout amount");
        }
        return amount;
    }
}
