package com.sushimei.sushimei.backend.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record MenuSelectionRuleResponse(
        Long id,
        Long selectionGroupId,
        SelectionRuleTargetType targetType,
        Long targetId,
        SelectionPricingPolicy pricingPolicy,
        BigDecimal referencePrice,
        BigDecimal fixedSurcharge,
        int priority,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public MenuSelectionRuleResponse {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(selectionGroupId, "selectionGroupId must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(pricingPolicy, "pricingPolicy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    static MenuSelectionRuleResponse from(MenuSelectionRule rule) {
        SelectionRuleTargetType targetType = rule.getTargetMenuItem() != null
                ? SelectionRuleTargetType.ITEM : SelectionRuleTargetType.TAG;
        Long targetId = targetType == SelectionRuleTargetType.ITEM
                ? rule.getTargetMenuItem().getId() : rule.getTargetTag().getId();
        return new MenuSelectionRuleResponse(rule.getId(), rule.getSelectionGroup().getId(), targetType, targetId,
                rule.getPricingPolicy(), rule.getReferencePriceAmount(), rule.getFixedSurchargeAmount(),
                rule.getPriority(), rule.isActive(), rule.getVersion(), rule.getCreatedAt(), rule.getUpdatedAt());
    }
}
