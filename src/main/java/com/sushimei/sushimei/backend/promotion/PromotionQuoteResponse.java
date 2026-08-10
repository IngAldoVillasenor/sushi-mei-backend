package com.sushimei.sushimei.backend.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record PromotionQuoteResponse(
        Instant quotedAt,
        String businessTimeZone,
        List<PromotionQuoteLineResponse> lines,
        BigDecimal catalogBaseSubtotal,
        BigDecimal configurationAdjustmentTotal,
        BigDecimal promotionAdjustmentTotal,
        BigDecimal total
) {
    public PromotionQuoteResponse {
        Objects.requireNonNull(quotedAt, "quotedAt must not be null");
        Objects.requireNonNull(businessTimeZone, "businessTimeZone must not be null");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
        Objects.requireNonNull(catalogBaseSubtotal, "catalogBaseSubtotal must not be null");
        Objects.requireNonNull(configurationAdjustmentTotal, "configurationAdjustmentTotal must not be null");
        Objects.requireNonNull(promotionAdjustmentTotal, "promotionAdjustmentTotal must not be null");
        Objects.requireNonNull(total, "total must not be null");
    }
}
