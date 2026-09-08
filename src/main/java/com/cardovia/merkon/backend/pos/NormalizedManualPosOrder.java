package com.cardovia.merkon.backend.pos;

import com.cardovia.merkon.backend.entity.OrderFulfillmentType;
import com.cardovia.merkon.backend.entity.OrderPaymentMethod;
import com.cardovia.merkon.backend.entity.OrderPaymentTiming;
import com.cardovia.merkon.backend.promotion.PromotionQuoteLineRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

record NormalizedManualPosOrder(UUID requestId, OrderFulfillmentType fulfillmentType, OrderPaymentMethod paymentMethod,
                                OrderPaymentTiming paymentTiming,
                                String deliveryAddress, String pickupName, BigDecimal cashDenomination,
                                List<PromotionQuoteLineRequest> lines,
                                List<NormalizedManualPricedLine> manualLines,
                                String fingerprint) {
}
