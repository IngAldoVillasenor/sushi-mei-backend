package com.sushimei.sushimei.backend.promotion;

import java.util.Objects;

public record PromotionTargetResponse(PromotionTargetType targetType, Long targetId) {
    public PromotionTargetResponse {
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
    }

    static PromotionTargetResponse from(PromotionTarget target) {
        if (target.getTargetMenuItem() != null) {
            return new PromotionTargetResponse(PromotionTargetType.ITEM, target.getTargetMenuItem().getId());
        }
        return new PromotionTargetResponse(PromotionTargetType.TAG, target.getTargetTag().getId());
    }
}
