package com.cardovia.merkon.backend.pos;

import com.cardovia.merkon.backend.entity.OrderPaymentMethod;
import java.math.BigDecimal;
import java.util.UUID;

record NormalizedOpenSale(UUID requestId,
                          String description,
                          BigDecimal amount,
                          OrderPaymentMethod paymentMethod,
                          BigDecimal cashDenomination,
                          String fingerprint) {
}
