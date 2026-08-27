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
        List<MenuQuoteGroupResponse> groups,
        List<DefaultComponentResponse> omittedComponents,
        String note
) {
    public MenuQuoteSelectionResponse {
        Objects.requireNonNull(menuItemId, "menuItemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(catalogUnitPrice, "catalogUnitPrice must not be null");
        Objects.requireNonNull(priceAdjustment, "priceAdjustment must not be null");
        groups = List.copyOf(Objects.requireNonNull(groups, "groups must not be null"));
        omittedComponents = List.copyOf(omittedComponents == null ? List.of() : omittedComponents);
    }

    /** Source-compatible result for configurations without occurrence-level customizations. */
    public MenuQuoteSelectionResponse(Long menuItemId,
                                      String name,
                                      int quantity,
                                      BigDecimal catalogUnitPrice,
                                      BigDecimal priceAdjustment,
                                      boolean displayOnTicket,
                                      List<MenuQuoteGroupResponse> groups) {
        this(menuItemId, name, quantity, catalogUnitPrice, priceAdjustment, displayOnTicket, groups, List.of(), null);
    }
}
