package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import java.math.BigDecimal;
import java.util.UUID;

record NormalizedOpenSale(UUID requestId,
                          String description,
                          BigDecimal amount,
                          OrderPaymentMethod paymentMethod,
                          BigDecimal cashDenomination,
                          String fingerprint) {
}
