package com.sushimei.sushimei.backend.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PromotionResponse(
        Long id,
        String name,
        boolean active,
        int priority,
        PromotionBenefitType benefitType,
        BigDecimal fixedUnitPrice,
        Integer buyQuantity,
        Integer rewardQuantity,
        Boolean repeat,
        LocalDate validFrom,
        LocalDate validUntil,
        Set<Integer> daysOfWeek,
        List<PromotionTargetResponse> targets,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
    public PromotionResponse {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(benefitType, "benefitType must not be null");
        daysOfWeek = Set.copyOf(Objects.requireNonNull(daysOfWeek, "daysOfWeek must not be null"));
        targets = List.copyOf(Objects.requireNonNull(targets, "targets must not be null"));
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    static PromotionResponse from(Promotion promotion) {
        List<PromotionTargetResponse> targets = promotion.getTargets().stream().map(PromotionTargetResponse::from)
                .sorted(Comparator.comparing(PromotionTargetResponse::targetType).thenComparing(PromotionTargetResponse::targetId))
                .toList();
        return new PromotionResponse(promotion.getId(), promotion.getName(), promotion.isActive(), promotion.getPriority(),
                promotion.getBenefitType(), promotion.getFixedUnitPriceAmount(), promotion.getBuyQuantity(),
                promotion.getRewardQuantity(), promotion.getRepeatEnabled(), promotion.getValidFrom(), promotion.getValidUntil(),
                promotion.getIsoWeekdays(), targets, promotion.getCreatedAt(), promotion.getUpdatedAt(), promotion.getVersion());
    }
}
