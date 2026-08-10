package com.sushimei.sushimei.backend.promotion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PromotionQuoteRequest(@NotEmpty List<@NotNull @Valid PromotionQuoteLineRequest> lines) {
    public PromotionQuoteRequest {
        lines = List.copyOf(lines == null ? List.of() : lines);
    }
}
