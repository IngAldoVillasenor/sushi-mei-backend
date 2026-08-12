package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ManualPosOrderResponse(
        Long id,
        UUID requestId,
        ManualOrderResult result,
        OrderSource orderSource,
        Long createdByUserId,
        OrderFulfillmentType fulfillmentType,
        OrderPaymentMethod paymentMethod,
        String deliveryAddress,
        String pickupName,
        BigDecimal cashDenomination,
        String status,
        Instant createdAt,
        List<ManualPosOrderLineResponse> lines,
        BigDecimal total
) {
    public ManualPosOrderResponse {
        lines = List.copyOf(lines == null ? List.of() : lines);
    }
}
