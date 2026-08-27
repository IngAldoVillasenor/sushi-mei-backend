package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteLineRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Client input intentionally excludes every price, total, promotion and reward identity. */
public record ManualPosOrderRequest(
        @NotNull UUID requestId,
        @NotNull OrderFulfillmentType fulfillmentType,
        @NotNull OrderPaymentMethod paymentMethod,
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

    /** Source-compatible catalog-only request. */
    public ManualPosOrderRequest(UUID requestId,
                                 OrderFulfillmentType fulfillmentType,
                                 OrderPaymentMethod paymentMethod,
                                 String deliveryAddress,
                                 String pickupName,
                                 BigDecimal cashDenomination,
                                 List<PromotionQuoteLineRequest> lines) {
        this(requestId, fulfillmentType, paymentMethod, deliveryAddress, pickupName, cashDenomination, lines, List.of());
    }
}
