package com.sushimei.sushimei.backend.promotion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record CreatePromotionRequest(
        @NotBlank String name,
        Boolean active,
        @NotNull @Min(0) Integer priority,
        @NotNull PromotionBenefitType benefitType,
        BigDecimal fixedUnitPrice,
        Integer buyQuantity,
        Integer rewardQuantity,
        Boolean repeat,
        LocalDate validFrom,
        LocalDate validUntil,
        @NotEmpty Set<@NotNull @Min(1) @jakarta.validation.constraints.Max(7) Integer> daysOfWeek,
        @NotEmpty List<@NotNull @Valid PromotionTargetRequest> targets
) {
}
