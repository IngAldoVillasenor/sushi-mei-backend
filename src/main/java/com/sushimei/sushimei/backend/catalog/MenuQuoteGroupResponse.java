package com.sushimei.sushimei.backend.catalog;

import java.util.List;
import java.util.Objects;

public record MenuQuoteGroupResponse(Long groupId, String name, List<MenuQuoteSelectionResponse> selections) {
    public MenuQuoteGroupResponse {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        selections = List.copyOf(Objects.requireNonNull(selections, "selections must not be null"));
    }
}
