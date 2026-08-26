package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteLineRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

record NormalizedManualPosOrder(UUID requestId, OrderFulfillmentType fulfillmentType, OrderPaymentMethod paymentMethod,
                                String deliveryAddress, String pickupName, BigDecimal cashDenomination,
                                List<PromotionQuoteLineRequest> lines,
                                List<NormalizedManualPricedLine> manualLines,
                                String fingerprint) {
}
