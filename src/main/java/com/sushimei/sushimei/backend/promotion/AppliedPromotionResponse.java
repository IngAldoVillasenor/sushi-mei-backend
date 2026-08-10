package com.sushimei.sushimei.backend.promotion;

import java.util.Objects;

public record AppliedPromotionResponse(Long id, String name, PromotionBenefitType benefitType) {
    public AppliedPromotionResponse {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(benefitType, "benefitType must not be null");
    }

    static AppliedPromotionResponse from(Promotion promotion) {
        return new AppliedPromotionResponse(promotion.getId(), promotion.getName(), promotion.getBenefitType());
    }
}
