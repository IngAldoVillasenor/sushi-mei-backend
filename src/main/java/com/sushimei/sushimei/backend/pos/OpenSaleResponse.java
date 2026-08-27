package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OpenSaleResponse(
        Long id,
        UUID requestId,
        OpenSaleResult result,
        OrderSource orderSource,
        Long createdByUserId,
        String description,
        int quantity,
        BigDecimal unitAmount,
        BigDecimal total,
        OrderPaymentMethod paymentMethod,
        BigDecimal cashDenomination,
        String status,
        Instant createdAt) {
}
