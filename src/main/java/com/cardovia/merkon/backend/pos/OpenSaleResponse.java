package com.cardovia.merkon.backend.pos;

import com.cardovia.merkon.backend.entity.OrderPaymentMethod;
import com.cardovia.merkon.backend.entity.OrderSource;
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
