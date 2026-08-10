package com.sushimei.sushimei.backend.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record MenuQuoteGroupRequest(
        @NotNull @Min(1) Long groupId,
        List<@NotNull @Valid MenuQuoteSelectionRequest> selections
) {
    public MenuQuoteGroupRequest {
        selections = selections == null ? List.of() : List.copyOf(selections);
    }
}
