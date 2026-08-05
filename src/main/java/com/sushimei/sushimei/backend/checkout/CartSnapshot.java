package com.sushimei.sushimei.backend.checkout;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record CartSnapshot(Long cartId, List<CartLineSnapshot> items, BigDecimal total) {

    public CartSnapshot {
        Objects.requireNonNull(cartId, "cartId must not be null");
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        Objects.requireNonNull(total, "total must not be null");
        if (total.signum() <= 0
                || total.scale() != CheckoutMoney.SCALE
                || total.precision() > CheckoutMoney.PRECISION) {
            throw new IllegalArgumentException("total must be a normalized positive checkout amount");
        }

        BigDecimal expectedTotal = items.stream()
                .map(CartLineSnapshot::lineTotal)
                .reduce(BigDecimal.ZERO.setScale(CheckoutMoney.SCALE), BigDecimal::add);
        if (!expectedTotal.equals(total)) {
            throw new IllegalArgumentException("total must equal the sum of line totals");
        }
    }
}
