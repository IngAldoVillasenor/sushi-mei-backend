package com.sushimei.sushimei.backend.tools;

import java.math.BigDecimal;
import java.util.Objects;

public record ResolvedMenuItem(String name, BigDecimal unitPrice) {

    public ResolvedMenuItem {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(unitPrice, "unitPrice must not be null");
        if (unitPrice.signum() <= 0) {
            throw new IllegalArgumentException("unitPrice must be positive");
        }
    }
}
