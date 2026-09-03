package com.sushimei.sushimei.backend.orderread;

import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderPaymentTiming;
import com.sushimei.sushimei.backend.entity.OrderSource;
import java.math.BigDecimal;
import java.time.Instant;

/** Lightweight, stable operational queue projection. */
public record OperationalOrderSummaryResponse(
        Long id,
        OrderSource orderSource,
        String status,
        OrderFulfillmentType fulfillmentType,
        OrderPaymentMethod paymentMethod,
        OrderPaymentTiming paymentTiming,
        boolean requiresPaymentCollection,
        String deliveryAddress,
        String pickupName,
        BigDecimal cashDenomination,
        String phoneNumber,
        BigDecimal total,
        Instant createdAt,
        boolean requiresPaymentValidation,
        boolean structuredLinesAvailable
) {
}
