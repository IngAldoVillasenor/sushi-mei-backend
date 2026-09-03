package com.sushimei.sushimei.backend.order;

import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import java.math.BigDecimal;

/** Final physical payment evidence collected for a READY pay-on-delivery order. */
public record OrderPaymentCollectionRequest(
        OrderPaymentMethod paymentMethod,
        BigDecimal cashDenomination) {
}
