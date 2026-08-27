package com.sushimei.sushimei.backend.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record MenuQuoteSelectionRequest(
        @NotNull @Min(1) Long menuItemId,
        @NotNull @Min(1) Integer quantity,
        List<@NotNull @Valid MenuQuoteGroupRequest> groups,
        List<@NotNull @Min(1) Long> omittedComponentIds,
        String note
) {
    public MenuQuoteSelectionRequest {
        groups = groups == null ? List.of() : List.copyOf(groups);
        omittedComponentIds = omittedComponentIds == null ? List.of() : List.copyOf(omittedComponentIds);
    }

    /** Source-compatible selection without occurrence-level customization. */
    public MenuQuoteSelectionRequest(Long menuItemId,
                                     Integer quantity,
                                     List<MenuQuoteGroupRequest> groups) {
        this(menuItemId, quantity, groups, List.of(), null);
    }
}
