package com.sushimei.sushimei.backend.orderread;

import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderSource;
import java.math.BigDecimal;
import java.time.Instant;

public record HistoricalOrderSummaryResponse(
        Long id,
        String externalOrderId,
        String externalReference,
        OrderSource orderSource,
        String status,
        OrderFulfillmentType fulfillmentType,
        OrderPaymentMethod paymentMethod,
        String pickupName,
        BigDecimal total,
        Instant createdAt,
        boolean structuredLinesAvailable
) {
}
