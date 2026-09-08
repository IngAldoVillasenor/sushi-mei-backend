package com.cardovia.merkon.backend.order;

import com.cardovia.merkon.backend.entity.OrderPaymentMethod;
import java.math.BigDecimal;

/** Final physical payment evidence collected for a READY pay-on-delivery order. */
public record OrderPaymentCollectionRequest(
        OrderPaymentMethod paymentMethod,
        BigDecimal cashDenomination) {
}
