package com.sushimei.sushimei.backend.pos;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Explicit client-priced line; this is intentionally separate from catalog-backed quote input. */
public record ManualPricedLineRequest(
        @NotBlank String lineKey,
        @NotBlank String description,
        @NotNull @Min(1) Integer quantity,
        @NotNull BigDecimal unitAmount) {
}
