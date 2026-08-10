package com.sushimei.sushimei.backend.promotion;

import com.sushimei.sushimei.backend.catalog.MenuItemQuoteResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record PromotionQuoteLineResponse(
        String lineKey,
        Long menuItemId,
        String name,
        int quantity,
        BigDecimal catalogBaseUnitPrice,
        BigDecimal chargedBaseUnitPrice,
        MenuItemQuoteResponse configuration,
        AppliedPromotionResponse appliedPromotion,
        BigDecimal promotionAdjustmentTotal,
        List<PromotionRewardQuoteResponse> rewards,
        BigDecimal lineTotal
) {
    public PromotionQuoteLineResponse {
        Objects.requireNonNull(lineKey, "lineKey must not be null");
        Objects.requireNonNull(menuItemId, "menuItemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(catalogBaseUnitPrice, "catalogBaseUnitPrice must not be null");
        Objects.requireNonNull(chargedBaseUnitPrice, "chargedBaseUnitPrice must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(promotionAdjustmentTotal, "promotionAdjustmentTotal must not be null");
        rewards = List.copyOf(Objects.requireNonNull(rewards, "rewards must not be null"));
        Objects.requireNonNull(lineTotal, "lineTotal must not be null");
    }
}
