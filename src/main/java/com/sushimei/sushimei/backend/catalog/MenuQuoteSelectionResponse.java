package com.sushimei.sushimei.backend.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record MenuQuoteSelectionResponse(
        Long menuItemId,
        String name,
        int quantity,
        BigDecimal catalogUnitPrice,
        BigDecimal priceAdjustment,
        boolean displayOnTicket,
        List<MenuQuoteGroupResponse> groups
) {
    public MenuQuoteSelectionResponse {
        Objects.requireNonNull(menuItemId, "menuItemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(catalogUnitPrice, "catalogUnitPrice must not be null");
        Objects.requireNonNull(priceAdjustment, "priceAdjustment must not be null");
        groups = List.copyOf(Objects.requireNonNull(groups, "groups must not be null"));
    }
}
