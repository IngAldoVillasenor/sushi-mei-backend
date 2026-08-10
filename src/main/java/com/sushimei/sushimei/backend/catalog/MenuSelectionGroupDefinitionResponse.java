package com.sushimei.sushimei.backend.catalog;

import java.util.List;
import java.util.Objects;

public record MenuSelectionGroupDefinitionResponse(
        MenuSelectionGroupResponse group,
        List<MenuSelectionRuleResponse> rules
) {
    public MenuSelectionGroupDefinitionResponse {
        Objects.requireNonNull(group, "group must not be null");
        rules = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
    }
}
