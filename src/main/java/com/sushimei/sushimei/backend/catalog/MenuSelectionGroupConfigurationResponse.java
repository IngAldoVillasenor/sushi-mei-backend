package com.sushimei.sushimei.backend.catalog;

import java.util.List;
import java.util.Objects;

public record MenuSelectionGroupConfigurationResponse(
        Long id,
        String name,
        int minSelections,
        int maxSelections,
        boolean allowDuplicates,
        List<MenuSelectionOptionResponse> options
) {
    public MenuSelectionGroupConfigurationResponse {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        options = List.copyOf(Objects.requireNonNull(options, "options must not be null"));
    }
}
