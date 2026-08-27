package com.sushimei.sushimei.backend.promotion;

import com.sushimei.sushimei.backend.catalog.MenuItemQuoteResponse;
import com.sushimei.sushimei.backend.catalog.DefaultComponentResponse;

import java.math.BigDecimal;
import java.util.List;
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
        BigDecimal total,
        List<DefaultComponentResponse> omittedComponents,
        String note
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
        omittedComponents = List.copyOf(omittedComponents == null ? List.of() : omittedComponents);
    }

    public PromotionRewardQuoteResponse(String sourceLineKey,
                                        int rewardOrdinal,
                                        AppliedPromotionResponse promotion,
                                        Long menuItemId,
                                        String name,
                                        BigDecimal catalogBaseUnitPrice,
                                        BigDecimal chargedBaseUnitPrice,
                                        MenuItemQuoteResponse configuration,
                                        BigDecimal configurationAdjustmentTotal,
                                        BigDecimal total) {
        this(sourceLineKey, rewardOrdinal, promotion, menuItemId, name, catalogBaseUnitPrice, chargedBaseUnitPrice,
                configuration, configurationAdjustmentTotal, total, List.of(), null);
    }
}
