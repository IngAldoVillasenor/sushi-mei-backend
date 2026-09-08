package com.cardovia.merkon.backend.order;

import com.cardovia.merkon.backend.entity.OrderPaymentMethod;
import com.cardovia.merkon.backend.entity.OrderPaymentTiming;
import java.math.BigDecimal;
import java.time.Instant;

/** Immutable response evidence for the atomic collect-payment/complete transition. */
public record OrderPaymentCollectionResponse(
        Long orderId,
        OrderLifecycleStatus previousStatus,
        OrderLifecycleStatus currentStatus,
        OrderPaymentTiming paymentTiming,
        OrderPaymentMethod paymentMethod,
        BigDecimal cashDenomination,
        Instant paymentCollectedAt,
        Long paymentCollectedByUserId) {
}
