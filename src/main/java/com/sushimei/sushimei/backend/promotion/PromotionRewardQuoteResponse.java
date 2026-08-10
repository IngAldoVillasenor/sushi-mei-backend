package com.sushimei.sushimei.backend.promotion;

import com.sushimei.sushimei.backend.catalog.MenuItemQuoteResponse;

import java.math.BigDecimal;
import java.util.Objects;

public record PromotionRewardQuoteResponse(
        String sourceLineKey,
        int rewardOrdinal,
        AppliedPromotionResponse promotion,
        Long menuItemId,
        String name,
        BigDecimal catalogBaseUnitPrice,
        BigDecimal chargedBaseUnitPrice,
        MenuItemQuoteResponse configuration,
        BigDecimal configurationAdjustmentTotal,
        BigDecimal total
) {
    public PromotionRewardQuoteResponse {
        Objects.requireNonNull(sourceLineKey, "sourceLineKey must not be null");
        Objects.requireNonNull(promotion, "promotion must not be null");
        Objects.requireNonNull(menuItemId, "menuItemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(catalogBaseUnitPrice, "catalogBaseUnitPrice must not be null");
        Objects.requireNonNull(chargedBaseUnitPrice, "chargedBaseUnitPrice must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(configurationAdjustmentTotal, "configurationAdjustmentTotal must not be null");
        Objects.requireNonNull(total, "total must not be null");
    }
}
