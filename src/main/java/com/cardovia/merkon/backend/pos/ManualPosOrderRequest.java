package com.cardovia.merkon.backend.pos;

import com.cardovia.merkon.backend.entity.OrderFulfillmentType;
import com.cardovia.merkon.backend.entity.OrderPaymentMethod;
import com.cardovia.merkon.backend.entity.OrderPaymentTiming;
import com.cardovia.merkon.backend.promotion.PromotionQuoteLineRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Client input intentionally excludes every price, total, promotion and reward identity. */
public record ManualPosOrderRequest(
        @NotNull UUID requestId,
        @NotNull OrderFulfillmentType fulfillmentType,
        OrderPaymentMethod paymentMethod,
        OrderPaymentTiming paymentTiming,
        String deliveryAddress,
        String pickupName,
        BigDecimal cashDenomination,
        List<@NotNull @Valid PromotionQuoteLineRequest> lines,
        List<@NotNull @Valid ManualPricedLineRequest> manualLines
) {
    public ManualPosOrderRequest {
        lines = List.copyOf(lines == null ? List.of() : lines);
        manualLines = List.copyOf(manualLines == null ? List.of() : manualLines);
    }

    /** Source-compatible request with manual-priced lines. */
    public ManualPosOrderRequest(UUID requestId,
                                 OrderFulfillmentType fulfillmentType,
                                 OrderPaymentMethod paymentMethod,
                                 String deliveryAddress,
                                 String pickupName,
                                 BigDecimal cashDenomination,
                                 List<PromotionQuoteLineRequest> lines,
                                 List<ManualPricedLineRequest> manualLines) {
        this(requestId, fulfillmentType, paymentMethod, null, deliveryAddress, pickupName, cashDenomination, lines, manualLines);
    }

    /** Source-compatible catalog-only request. */
    public ManualPosOrderRequest(UUID requestId,
                                 OrderFulfillmentType fulfillmentType,
                                 OrderPaymentMethod paymentMethod,
                                 String deliveryAddress,
                                 String pickupName,
                                 BigDecimal cashDenomination,
                                 List<PromotionQuoteLineRequest> lines) {
        this(requestId, fulfillmentType, paymentMethod, null, deliveryAddress, pickupName, cashDenomination, lines, List.of());
    }
}
