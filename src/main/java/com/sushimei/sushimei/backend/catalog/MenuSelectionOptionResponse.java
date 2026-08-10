package com.sushimei.sushimei.backend.catalog;

import java.math.BigDecimal;
import java.util.Objects;

public record MenuSelectionOptionResponse(
        Long menuItemId,
        String name,
        String category,
        BigDecimal catalogPrice,
        boolean available,
        boolean requiresConfiguration,
        BigDecimal priceAdjustment
) {
    public MenuSelectionOptionResponse {
        Objects.requireNonNull(menuItemId, "menuItemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(catalogPrice, "catalogPrice must not be null");
        Objects.requireNonNull(priceAdjustment, "priceAdjustment must not be null");
    }
}
