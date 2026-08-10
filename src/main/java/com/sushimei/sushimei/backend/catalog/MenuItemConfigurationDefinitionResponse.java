package com.sushimei.sushimei.backend.catalog;

import java.util.List;
import java.util.Objects;

public record MenuItemConfigurationDefinitionResponse(
        Long menuItemId,
        String name,
        long version,
        List<CatalogTagSummary> tags,
        List<MenuSelectionGroupDefinitionResponse> groups
) {
    public MenuItemConfigurationDefinitionResponse {
        Objects.requireNonNull(menuItemId, "menuItemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        tags = List.copyOf(Objects.requireNonNull(tags, "tags must not be null"));
        groups = List.copyOf(Objects.requireNonNull(groups, "groups must not be null"));
    }
}
