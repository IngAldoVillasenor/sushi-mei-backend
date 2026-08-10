package com.sushimei.sushimei.backend.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record MenuItemQuoteResponse(
        Long menuItemId,
        String name,
        int quantity,
        BigDecimal baseUnitPrice,
        BigDecimal baseTotal,
        List<MenuQuoteGroupResponse> groups,
        BigDecimal unitAdjustmentTotal,
        BigDecimal unitTotal,
        BigDecimal total
) {
    public MenuItemQuoteResponse {
        Objects.requireNonNull(menuItemId, "menuItemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(baseUnitPrice, "baseUnitPrice must not be null");
        Objects.requireNonNull(baseTotal, "baseTotal must not be null");
        groups = List.copyOf(Objects.requireNonNull(groups, "groups must not be null"));
        Objects.requireNonNull(unitAdjustmentTotal, "unitAdjustmentTotal must not be null");
        Objects.requireNonNull(unitTotal, "unitTotal must not be null");
        Objects.requireNonNull(total, "total must not be null");
    }
}
