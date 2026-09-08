package com.cardovia.merkon.backend.orderread;

import com.cardovia.merkon.backend.entity.OrderFulfillmentType;
import com.cardovia.merkon.backend.entity.OrderPaymentMethod;
import com.cardovia.merkon.backend.entity.OrderPaymentTiming;
import com.cardovia.merkon.backend.entity.OrderSource;
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
