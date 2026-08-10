package com.sushimei.sushimei.backend.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record MenuItemConfigurationResponse(
        Long menuItemId,
        String name,
        boolean standaloneOrderable,
        BigDecimal basePrice,
        boolean requiresConfiguration,
        List<MenuSelectionGroupConfigurationResponse> groups
) {
    public MenuItemConfigurationResponse {
        Objects.requireNonNull(menuItemId, "menuItemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(basePrice, "basePrice must not be null");
        groups = List.copyOf(Objects.requireNonNull(groups, "groups must not be null"));
    }
}
