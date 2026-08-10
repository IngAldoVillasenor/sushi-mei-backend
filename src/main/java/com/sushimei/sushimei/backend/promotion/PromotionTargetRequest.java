package com.sushimei.sushimei.backend.promotion;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PromotionTargetRequest(
        @NotNull PromotionTargetType targetType,
        @NotNull @Min(1) Long targetId
) {
}
