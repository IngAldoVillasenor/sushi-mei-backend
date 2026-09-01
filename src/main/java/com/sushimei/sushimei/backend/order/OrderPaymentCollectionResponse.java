package com.sushimei.sushimei.backend.order;

import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.entity.OrderPaymentTiming;
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
