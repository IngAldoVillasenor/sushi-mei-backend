package com.sushimei.sushimei.backend.catalog;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateMenuSelectionRuleRequest(
        @NotNull SelectionRuleTargetType targetType,
        @NotNull @Min(1) Long targetId,
        @NotNull SelectionPricingPolicy pricingPolicy,
        BigDecimal referencePrice,
        BigDecimal fixedSurcharge,
        @NotNull @Min(0) Integer priority,
        @NotNull Boolean active,
        @NotNull @Min(0) Long version
) {
}
